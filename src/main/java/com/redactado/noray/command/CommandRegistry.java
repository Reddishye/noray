package com.redactado.noray.command;

import com.redactado.noray.kernel.api.KernelContext;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Central registry and dispatcher for Noray shell commands.
 *
 * <h2>Supported shell grammar (in precedence order)</h2>
 * <pre>
 *  statement ::= pipeline (';' pipeline)*
 *              | pipeline ('&&' pipeline)*
 *              | pipeline ('||' pipeline)*
 *  pipeline  ::= command ('|' command)+
 *  command   ::= word+ redirection*
 *  redirection ::= '>'  word   (stdout overwrite)
 *                | '>>' word   (stdout append)
 *                | '2>&1'      (merge stderr into stdout)
 * </pre>
 * Variable expansion: {@code $VAR} and {@code ${VAR}} are resolved against the environment
 * before tokenisation. {@code $?} expands to the previous exit code.
 */
public final class CommandRegistry {

    private final Map<String, Command>  commands = new ConcurrentHashMap<>();
    private final Map<String, String>   aliasMap = new ConcurrentHashMap<>();

    // ── Registration ─────────────────────────────────────────────────────

    public void register(Command command) {
        Objects.requireNonNull(command, "command cannot be null");
        commands.put(command.name().toLowerCase(), command);
        for (String alias : command.aliases()) {
            aliasMap.put(alias.toLowerCase(), command.name().toLowerCase());
        }
    }

    public Optional<Command> find(String nameOrAlias) {
        if (nameOrAlias == null) return Optional.empty();
        String n = nameOrAlias.toLowerCase();
        Command cmd = commands.get(n);
        if (cmd == null && aliasMap.containsKey(n)) {
            cmd = commands.get(aliasMap.get(n));
        }
        return Optional.ofNullable(cmd);
    }

    public Collection<Command> getAllCommands() {
        return Collections.unmodifiableCollection(commands.values());
    }

    // ── Top-level dispatch ────────────────────────────────────────────────

    /**
     * Dispatch a single raw input line, handling {@code ;}, {@code &&}, {@code ||},
     * pipes, and redirections. Returns the exit code of the last command in the chain.
     */
    public int dispatch(String line, KernelContext context,
                        InputStream in, PrintStream out, PrintStream err) {
        return dispatch(line, context, in, out, err, Map.of(), 0);
    }

    /**
     * Dispatch with explicit environment and previous exit code ({@code $?}).
     */
    public int dispatch(String line, KernelContext context,
                        InputStream in, PrintStream out, PrintStream err,
                        Map<String, String> env, int prevExitCode) {
        if (line == null || line.isBlank()) return 0;

        // Expand environment variables before parsing
        String expanded = expandVariables(line, env, prevExitCode);

        // Split on ;  (lowest precedence — sequential unconditional)
        // then on && / || (short-circuit)
        // Pipelines are within a single command segment.
        int code = 0;
        for (Statement stmt : splitStatements(expanded)) {
            code = runStatement(stmt, context, in, out, err, env, code);
        }
        return code;
    }

    // ── Statement splitting ───────────────────────────────────────────────

    private record Statement(String text, Operator op) {}
    private enum Operator { SEMICOLON, AND, OR, NONE }

    private static final Pattern STATEMENT_SPLIT =
            Pattern.compile("(?<![|&])(?:;|&&|\\|\\|)(?![|&])");

    private List<Statement> splitStatements(String line) {
        List<Statement> result = new ArrayList<>();
        Matcher m = STATEMENT_SPLIT.matcher(line);
        int last = 0;
        Operator pendingOp = Operator.NONE;
        while (m.find()) {
            result.add(new Statement(line.substring(last, m.start()).strip(), pendingOp));
            String sep = m.group();
            pendingOp = switch (sep) {
                case "&&" -> Operator.AND;
                case "||" -> Operator.OR;
                default   -> Operator.SEMICOLON;
            };
            last = m.end();
        }
        result.add(new Statement(line.substring(last).strip(), pendingOp));
        return result;
    }

    private int runStatement(Statement stmt, KernelContext context,
                             InputStream in, PrintStream out, PrintStream err,
                             Map<String, String> env, int prevCode) {
        return switch (stmt.op()) {
            case AND  -> prevCode != 0 ? prevCode : runPipeline(stmt.text(), context, in, out, err, env, prevCode);
            case OR   -> prevCode == 0 ? prevCode : runPipeline(stmt.text(), context, in, out, err, env, prevCode);
            default   -> runPipeline(stmt.text(), context, in, out, err, env, prevCode);
        };
    }

    // ── Pipeline execution ────────────────────────────────────────────────

    /**
     * Execute a pipeline: {@code cmd1 | cmd2 | cmd3}.
     * Each command's stdout feeds the next command's stdin.
     */
    private int runPipeline(String segment, KernelContext context,
                            InputStream baseIn, PrintStream baseOut, PrintStream err,
                            Map<String, String> env, int prevCode) {
        if (segment.isBlank()) return 0;

        // Split on unquoted single-pipe (not &&, not ||)
        List<String> stages = splitPipe(segment);

        if (stages.size() == 1) {
            return runRedirected(stages.get(0), context, baseIn, baseOut, err, env, prevCode);
        }

        // Multi-stage pipeline
        InputStream currentIn = baseIn;
        int code = 0;
        List<PipedOutputStream> openPipes = new ArrayList<>();

        for (int i = 0; i < stages.size(); i++) {
            boolean last = (i == stages.size() - 1);
            String stage = stages.get(i).strip();

            if (last) {
                code = runRedirected(stage, context, currentIn, baseOut, err, env, prevCode);
            } else {
                try {
                    PipedOutputStream pout = new PipedOutputStream();
                    PipedInputStream  pin  = new PipedInputStream(pout, 65536);
                    openPipes.add(pout);
                    PrintStream pps = new PrintStream(pout, true, StandardCharsets.UTF_8);
                    final InputStream stageIn = currentIn;
                    int stageCode = runRedirected(stage, context, stageIn, pps, err, env, prevCode);
                    pps.flush();
                    pps.close();
                    currentIn = pin;
                    code = stageCode;
                } catch (IOException e) {
                    err.printf("noray: pipe: %s%n", e.getMessage());
                    return 1;
                }
            }
        }
        return code;
    }

    private List<String> splitPipe(String segment) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if      (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"'  && !inSingle) inDouble = !inDouble;
            else if (c == '|'  && !inSingle && !inDouble) {
                // Ensure not && or ||
                if (i + 1 < segment.length() && segment.charAt(i + 1) == '|') {
                    cur.append("||"); i++; // it's ||, not a pipe
                } else {
                    result.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            if (c != '|' || cur.length() > 0 || !result.isEmpty()) cur.append(c);
            else cur.append(c);
        }
        result.add(cur.toString());
        return result;
    }

    // ── Redirection ───────────────────────────────────────────────────────

    private int runRedirected(String raw, KernelContext context,
                              InputStream in, PrintStream out, PrintStream err,
                              Map<String, String> env, int prevCode) {
        // Parse redirections: > file, >> file, 2>&1
        String cmd = raw;
        PrintStream effectiveOut = out;
        PrintStream effectiveErr = err;
        boolean mergeErr = false;
        List<Closeable> opened = new ArrayList<>();

        // 2>&1  (merge stderr → stdout)
        if (cmd.contains("2>&1")) {
            mergeErr = true;
            cmd = cmd.replace("2>&1", "").strip();
        }

        // >> append
        Matcher appendM = Pattern.compile(">>\\s*(\\S+)").matcher(cmd);
        if (appendM.find()) {
            String path = appendM.group(1);
            cmd = cmd.substring(0, appendM.start()).strip();
            try {
                FileOutputStream fos = new FileOutputStream(path, true);
                opened.add(fos);
                effectiveOut = new PrintStream(fos, true, StandardCharsets.UTF_8);
            } catch (IOException e) {
                err.printf("noray: %s: %s%n", path, e.getMessage());
                return 1;
            }
        } else {
            // > overwrite
            Matcher overM = Pattern.compile("(?<![>])>(?![>])\\s*(\\S+)").matcher(cmd);
            if (overM.find()) {
                String path = overM.group(1);
                cmd = cmd.substring(0, overM.start()).strip();
                try {
                    FileOutputStream fos = new FileOutputStream(path, false);
                    opened.add(fos);
                    effectiveOut = new PrintStream(fos, true, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    err.printf("noray: %s: %s%n", path, e.getMessage());
                    return 1;
                }
            }
        }

        if (mergeErr) effectiveErr = effectiveOut;

        try {
            return runCommand(cmd.strip(), context, in, effectiveOut, effectiveErr, env, prevCode);
        } finally {
            for (Closeable c : opened) {
                try { c.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ── Command execution ─────────────────────────────────────────────────

    private int runCommand(String line, KernelContext context,
                           InputStream in, PrintStream out, PrintStream err,
                           Map<String, String> env, int prevCode) {
        if (line.isBlank()) return 0;

        List<String> tokens = parseTokens(line);
        if (tokens.isEmpty()) return 0;

        String cmdName = tokens.get(0);
        Optional<Command> cmdOpt = find(cmdName);
        if (cmdOpt.isEmpty()) {
            err.printf("noray: %s: command not found%n", cmdName);
            return 127;
        }

        List<String>        positionalArgs = new ArrayList<>();
        Map<String, String> flags          = new LinkedHashMap<>();
        Set<String>         booleanFlags   = new LinkedHashSet<>();

        parseArguments(cmdOpt.get(), tokens, positionalArgs, flags, booleanFlags);

        CommandInvocation invocation = CommandInvocation.builder()
                .rawCommandLine(line)
                .commandName(cmdName)
                .args(positionalArgs)
                .flags(flags)
                .booleanFlags(booleanFlags)
                .environment(new java.util.HashMap<>(env))
                .lastExitCode(prevCode)
                .context(context)
                .in(in)
                .out(out)
                .err(err)
                .build();

        try {
            return cmdOpt.get().execute(invocation);
        } catch (Throwable t) {
            err.printf("noray: %s: %s%n", cmdName,
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            return 1;
        }
    }

    /**
     * Splits {@code tokens[1..]} into arguments and flags. {@code --} ends option parsing; a single-letter flag takes a
     * value only when the command lists it in {@link Command#valueFlags()}; clustered letters ({@code -abc}) are switches.
     */
    private static final Set<String> UNIVERSAL_SWITCHES = Set.of("help");

    static void parseArguments(Command command, List<String> tokens, List<String> positionalArgs,
                               Map<String, String> flags, Set<String> booleanFlags) {
        Set<String> valued = command.valueFlags();
        Set<String> switches = command.switchFlags();
        boolean optionsEnded = false;
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean nextIsWord = i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("-");
            if (optionsEnded || token.equals("-") || !token.startsWith("-")) {
                positionalArgs.add(token);
            } else if (token.equals("--")) {
                optionsEnded = true;
            } else if (token.startsWith("--")) {
                String body = token.substring(2);
                if (body.contains("=")) {
                    String[] kv = body.split("=", 2);
                    flags.put(kv[0], kv[1]);
                } else if (!switches.contains(body) && !UNIVERSAL_SWITCHES.contains(body) && nextIsWord) {
                    flags.put(body, tokens.get(++i));
                } else {
                    booleanFlags.add(body);
                }
            } else {
                String body = token.substring(1);
                if (body.length() == 1 && valued.contains(body) && nextIsWord) {
                    flags.put(body, tokens.get(++i));
                } else if (body.length() > 1 && valued.contains(body.substring(0, 1))) {
                    flags.put(body.substring(0, 1), body.substring(1)); // -n20
                } else {
                    for (char ch : body.toCharArray()) booleanFlags.add(String.valueOf(ch));
                }
            }
        }
    }

    // ── Tokeniser ─────────────────────────────────────────────────────────

    /** Shell-style tokeniser: respects single and double quotes. */
    public static List<String> parseTokens(String line) {
        List<String>  tokens  = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false, inDouble = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    // ── Variable expansion ────────────────────────────────────────────────

    private static final Pattern VAR_PATTERN =
            Pattern.compile("\\$\\{([^}]+)}|\\$(\\w+|\\?)");

    /** Expands {@code $VAR}, {@code ${VAR}}, and {@code $?} in the line, except inside single quotes (as POSIX shells do). */
    static String expandVariables(String line, Map<String, String> env, int lastExitCode) {
        StringBuilder out = new StringBuilder();
        StringBuilder chunk = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                if (!inSingle) {
                    out.append(expandChunk(chunk.toString(), env, lastExitCode));
                    chunk.setLength(0);
                    out.append(c);
                } else {
                    out.append(chunk).append(c); // literal, as typed
                    chunk.setLength(0);
                }
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) inDouble = !inDouble;
            chunk.append(c);
        }
        out.append(inSingle ? chunk.toString() : expandChunk(chunk.toString(), env, lastExitCode));
        return out.toString();
    }

    private static String expandChunk(String text, Map<String, String> env, int lastExitCode) {
        Matcher m = VAR_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            String value = name.equals("?") ? String.valueOf(lastExitCode)
                         : env.getOrDefault(name, System.getenv().getOrDefault(name, ""));
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

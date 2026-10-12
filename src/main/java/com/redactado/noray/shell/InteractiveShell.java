package com.redactado.noray.shell;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandRegistry;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelPhase;
import com.redactado.noray.log.CircularLogJournal;
import com.redactado.noray.log.LogEntry;
import com.redactado.noray.log.LogLevel;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class InteractiveShell implements Runnable {

    private static final String ANSI_RESET  = "\u001B[0m";
    private static final String ANSI_BOLD   = "\u001B[1m";
    private static final String ANSI_DIM    = "\u001B[2m";
    private static final String ANSI_RED    = "\u001B[31m";
    private static final String ANSI_GREEN  = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE   = "\u001B[34m";
    private static final String ANSI_CYAN   = "\u001B[36m";
    private static final String ANSI_WHITE  = "\u001B[37m";

    private final CommandRegistry        registry;
    private final KernelContext          context;
    private final CircularLogJournal     journal;
    private final Runnable               shutdownHook;
    private final InputStream            in;
    private final PrintStream            out;
    private final PrintStream            err;
    private final Map<String, String>    environment = new LinkedHashMap<>();
    private final AtomicBoolean          running     = new AtomicBoolean(false);

    private volatile int lastExitCode = 0;
    private volatile LineReader activeReader = null;
    private volatile Terminal activeTerminal = null;

    public InteractiveShell(CommandRegistry registry, KernelContext context, CircularLogJournal journal, Runnable shutdownHook,
                            InputStream in, PrintStream out, PrintStream err) {
        this.registry     = Objects.requireNonNull(registry,     "registry cannot be null");
        this.context      = Objects.requireNonNull(context,      "context cannot be null");
        this.journal      = Objects.requireNonNull(journal,      "journal cannot be null");
        this.shutdownHook = Objects.requireNonNull(shutdownHook, "shutdownHook cannot be null");
        this.in           = Objects.requireNonNull(in,           "in cannot be null");
        this.out          = Objects.requireNonNull(out,          "out cannot be null");
        this.err          = Objects.requireNonNull(err,          "err cannot be null");

        // Kill JLine's internal fallback warnings
        java.util.logging.Logger.getLogger("org.jline").setLevel(java.util.logging.Level.OFF);
    }

    @Override
    public void run() {
        // Wait for Kernel to finish its booting state before we hijack the streams
        while (context.getPhase() != KernelPhase.RUNNING) {
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
        }

        PrintStream originalErr = System.err;
        running.set(true);

        try {
            // JDK logging writes to System.err: route it through printSafe so log lines do not break the prompt.
            // Bytes are collected per line and decoded together, so multi-byte UTF-8 characters survive.
            System.setErr(new PrintStream(new OutputStream() {
                private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
                @Override
                public void write(int b) {
                    if (b == '\n') {
                        printSafe(line.toString(StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(b);
                    }
                }
            }, true, StandardCharsets.UTF_8));

            runWithJLine();
        } catch (Throwable e) {
            originalErr.println(ANSI_RED + "Fatal shell error: " + e.getMessage() + ANSI_RESET);
        } finally {
            running.set(false);
            System.setErr(originalErr); // Restore normal logging
            if (context.getPhase() == KernelPhase.RUNNING) {
                Thread.ofVirtual().name("noray-shell-eof-shutdown").start(shutdownHook);
            }
        }
    }

    public void stop() {
        running.set(false);
    }

    /**
     * Safely prints a message to the terminal EVEN IF the user is currently typing.
     * Replaces the broken `reader.printAbove()` which fails in Gradle.
     */
    private synchronized void printSafe(String message) {
        if (activeReader != null && activeTerminal != null && activeReader.isReading()) {
            // 1. Wipe the current prompt & user input visually
            activeReader.callWidget(LineReader.CLEAR);
            // 2. Print the background log
            activeTerminal.writer().println(message);
            // 3. Redraw the prompt and restore the user's typed text instantly
            activeReader.callWidget(LineReader.REDRAW_LINE);
            activeReader.callWidget(LineReader.REDISPLAY);
            activeTerminal.writer().flush();
        } else if (activeTerminal != null) {
            activeTerminal.writer().println(message);
            activeTerminal.writer().flush();
        } else {
            out.println(message);
        }
    }

    private Terminal createTerminal() throws IOException {
        if (System.console() != null) {
            return TerminalBuilder.builder().system(true).build();
        } else {
            // No console (e.g. `gradle run`): a dumb-color terminal of fixed size, so JLine sends no size probes, whose
            // replies would swallow the first character typed.
            Terminal t = TerminalBuilder.builder()
                    .streams(in, out)
                    .system(false)
                    .type("dumb-color")
                    .color(true)
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            t.setSize(new Size(120, 40));
            return t;
        }
    }

    private void runWithJLine() throws IOException {
        try (Terminal terminal = createTerminal()) {
            this.activeTerminal = terminal;

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new DefaultParser())
                    .completer(buildCompleter())
                    .highlighter(new NorayHighlighter())
                    .history(new DefaultHistory())
                    .variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".noray_history"))
                    .variable(LineReader.HISTORY_SIZE, 500)
                    .variable(LineReader.BELL_STYLE, "none")
                    .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                    .option(LineReader.Option.INSERT_TAB, false)
                    .build();

            this.activeReader = reader;

            // Route our custom Telemetry/Journal logs through the safe printer
            Consumer<LogEntry> asyncLogListener = entry -> {
                if (running.get()) {
                    String color = entry.level() == LogLevel.ERROR ? ANSI_RED :
                            entry.level() == LogLevel.WARN ? ANSI_YELLOW : ANSI_DIM;
                    printSafe(String.format("%s[%s] %s: %s%s",
                            color, entry.level(), entry.source(), entry.message(), ANSI_RESET));
                }
            };

            journal.addStreamListener(asyncLogListener);
            printBanner(s -> { terminal.writer().print(s); terminal.writer().flush(); });

            try {
                while (running.get() && context.getPhase() == KernelPhase.RUNNING) {
                    try {
                        String line = reader.readLine(buildPrompt(), buildRightPrompt(), (MaskingCallback) null, null);

                        if (line == null) break;
                        line = line.strip();
                        if (line.isEmpty()) continue;

                        lastExitCode = registry.dispatch(
                                line, context, in, out, err, Map.copyOf(environment), lastExitCode);

                    } catch (UserInterruptException e) {
                        terminal.writer().println(ANSI_RED + "ctrl-c (abandon ship!)" + ANSI_RESET);
                        lastExitCode = 130;
                    } catch (EndOfFileException e) {
                        terminal.writer().println(ANSI_DIM + "Dropping anchor. Goodbye." + ANSI_RESET);
                        break;
                    } catch (Throwable t) {
                        if (running.get() && context.getPhase() == KernelPhase.RUNNING) {
                            printSafe(ANSI_RED + "☠️ shell error: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()) + ANSI_RESET);
                        }
                        break;
                    }
                }
            } finally {
                journal.removeStreamListener(asyncLogListener);
                this.activeReader = null;
                this.activeTerminal = null;
            }
        }
    }

    private String buildPrompt() {
        return ANSI_BOLD + ANSI_CYAN + "⚓ noray "
                + (lastExitCode == 0 ? ANSI_BLUE : ANSI_RED) + "❯ " + ANSI_RESET;
    }

    private String buildRightPrompt() {
        if (lastExitCode == 0) {
            return ANSI_DIM + ANSI_GREEN + "✔" + ANSI_RESET;
        } else {
            return ANSI_BOLD + ANSI_RED + "✘ [" + lastExitCode + "]" + ANSI_RESET;
        }
    }

    private void printBanner(java.util.function.Consumer<String> printer) {
        printer.accept(
                ANSI_BOLD + ANSI_CYAN   + " _   _                          \n"
                        + ANSI_BOLD + ANSI_BLUE   + "| \\ | | ___  _ __ __ _ _   _  \n"
                        + ANSI_BOLD + ANSI_BLUE   + "|  \\| |/ _ \\| '__/ _` | | | | \n"
                        + ANSI_BOLD + ANSI_CYAN   + "| |\\  | (_) | | | (_| | |_| | \n"
                        + ANSI_BOLD + ANSI_CYAN   + "|_| \\_|\\___/|_|  \\__,_|\\__, | \n"
                        + ANSI_BOLD + ANSI_CYAN   + "                       |___/  "
                        + ANSI_RESET + ANSI_YELLOW + "☠️ #MadeForPirates" + ANSI_RESET + "\n\n");

        printer.accept(ANSI_DIM + "Ahoy! Type " + ANSI_RESET + ANSI_BOLD + ANSI_GREEN + "help" + ANSI_RESET + ANSI_DIM
                + " for commands. "
                + ANSI_BOLD + ANSI_YELLOW + "Tab" + ANSI_RESET + ANSI_DIM + " to complete. "
                + ANSI_BOLD + ANSI_RED + "Ctrl-D" + ANSI_RESET + ANSI_DIM + " to halt.\n\n" + ANSI_RESET);
    }

    private Completer buildCompleter() {
        return (reader, line, candidates) -> {
            List<String> words = line.words();
            if (words.size() <= 1) {
                String prefix = words.isEmpty() ? "" : words.get(0).toLowerCase();
                registry.getAllCommands().stream()
                        .flatMap(cmd -> {
                            List<String> names = new ArrayList<>();
                            names.add(cmd.name());
                            names.addAll(cmd.aliases());
                            return names.stream();
                        })
                        .filter(n -> n.startsWith(prefix))
                        .sorted()
                        .forEach(n -> candidates.add(new Candidate(n)));
                return;
            }
            registry.find(words.get(0)).ifPresent(cmd -> {
                List<String> args = new ArrayList<>(words.subList(1, line.wordIndex() + 1));
                String partial = args.isEmpty() ? "" : args.getLast();
                try {
                    for (String c : cmd.complete(context, args)) {
                        if (c.startsWith(partial)) {
                            boolean dir = c.endsWith("/");
                            candidates.add(new Candidate(c, c, null, null, null, null, !dir));
                        }
                    }
                } catch (RuntimeException ignored) {
                    // completion must never break the prompt
                }
            });
        };
    }

    private class NorayHighlighter implements Highlighter {
        @Override
        public AttributedString highlight(LineReader reader, String buffer) {
            AttributedStringBuilder builder = new AttributedStringBuilder();
            if (buffer.isEmpty()) return builder.toAttributedString();

            boolean inQuotes = false;
            boolean firstWord = true;
            StringBuilder currentWord = new StringBuilder();

            for (int i = 0; i < buffer.length(); i++) {
                char c = buffer.charAt(i);
                if (c == '"' || c == '\'') {
                    inQuotes = !inQuotes;
                    builder.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
                    builder.append(c);
                } else if (Character.isWhitespace(c) && !inQuotes) {
                    if (firstWord && !currentWord.isEmpty()) firstWord = false;
                    builder.style(AttributedStyle.DEFAULT);
                    builder.append(c);
                    currentWord.setLength(0);
                } else {
                    currentWord.append(c);
                    if (inQuotes) {
                        builder.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
                    } else if (firstWord) {
                        builder.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold());
                    } else if (c == '-') {
                        builder.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
                    } else {
                        builder.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE));
                    }
                    builder.append(c);
                }
            }
            return builder.toAttributedString();
        }

        @Override public void setErrorPattern(Pattern errorPattern) {}
        @Override public void setErrorIndex(int errorIndex) {}
    }
}
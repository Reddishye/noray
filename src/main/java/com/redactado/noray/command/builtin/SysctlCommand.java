package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.kernel.api.Configuration;
import com.redactado.noray.kernel.api.KernelContext;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Implements {@code sysctl} — query and modify kernel configuration parameters at runtime.
 * Mirrors the Linux sysctl command.
 */
public final class SysctlCommand implements Command {

    private static final String PREFIX = "noray.";
    private static final String DEFAULT_CONF = "/etc/noray.conf";

    @Override public Set<String> valueFlags() { return Set.of("p"); }

    @Override public Set<String> switchFlags() { return Set.of("all", "values", "ignore", "version"); }

    @Override public String name() { return "sysctl"; }
    @Override public String description() { return "Query and modify kernel configuration parameters"; }
    @Override public String synopsis() { return "sysctl [-a|-n|-e|-p[FILE]] [name[=value]...]"; }

    @Override
    public String longHelp() {
        return """
            Usage: sysctl [OPTION]... [VARIABLE[=VALUE]]...

            Options:
              -a, --all              display all variables
              -n, --values           print only the variable value, not the name
              -e, --ignore           ignore unknown variables (no error)
              -p, --load[=FILE]      load settings from file (default: /etc/noray.conf)
                  --help             display this help and exit
                  --version          output version information and exit

            Variables:
              Variables are scoped under the "noray." prefix.
              Example: sysctl noray.shell.prompt="noray# "
                       sysctl -n noray.log.level

            Special variables:
              noray.kernel.version       kernel version (read-only)
              noray.kernel.build_time    kernel build time (read-only)
              noray.kernel.phase         current kernel phase (read-only)
              noray.shell.prompt         shell prompt format
              noray.log.level            logging level (TRACE, DEBUG, INFO, WARN, ERROR)
              noray.service.timeout      default service timeout in seconds
            """;
    }

    @Override public CommandCategory category() { return CommandCategory.KERNEL; }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        var out = inv.out();
        var err = inv.err();
        var flags = inv.flags();
        var booleanFlags = inv.booleanFlags();
        var args = inv.args();
        var ctx = inv.context();

        if (booleanFlags.contains("help") || flags.containsKey("help")) {
            out.println(longHelp());
            return 0;
        }
        if (booleanFlags.contains("version") || flags.containsKey("version")) {
            out.println("sysctl (Noray) 1.0");
            return 0;
        }

        boolean showAll = booleanFlags.contains("a") || booleanFlags.contains("all");
        boolean valuesOnly = booleanFlags.contains("n") || booleanFlags.contains("values");
        boolean ignoreUnknown = booleanFlags.contains("e") || booleanFlags.contains("ignore");
        String loadFile = flags.getOrDefault("p", flags.get("load"));
        if (loadFile == null && (booleanFlags.contains("p") || booleanFlags.contains("load"))) {
            loadFile = DEFAULT_CONF;
        }

        Configuration config = ctx.getConfiguration();

        if (loadFile != null) {
            return loadFromFile(Path.of(loadFile), config, valuesOnly, ignoreUnknown, out, err);
        }

        if (showAll) {
            for (String key : config.getAllKeys()) {
                String value = config.get(key, "");
                if (valuesOnly) {
                    out.println(value);
                } else {
                    out.printf("%s = %s%n", key, value);
                }
            }
            return 0;
        }

        // Handle variable assignments (name=value) and queries
        int exitCode = 0;
        for (String arg : args) {
            if (arg.contains("=")) {
                // Assignment
                String[] kv = arg.split("=", 2);
                if (!applyAssignment(config, kv[0].trim(), kv[1].trim(), valuesOnly, ignoreUnknown, out, err)) {
                    exitCode = 1;
                }
            } else {
                // Query
                String key = arg.trim();
                if (!key.startsWith(PREFIX)) {
                    if (!ignoreUnknown) {
                        err.printf("sysctl: unknown variable '%s'%n", key);
                        exitCode = 1;
                    }
                    continue;
                }

                String value = config.get(key, "");
                if (valuesOnly) {
                    out.println(value);
                } else {
                    out.printf("%s = %s%n", key, value);
                }
            }
        }

        // If no args and not -a, show usage
        if (args.isEmpty() && !showAll) {
            out.println("Usage: sysctl [options] [variable[=value]...]");
            out.println("Try 'sysctl --help' for more information.");
            return 1;
        }

        return exitCode;
    }

    /** Applies a single {@code key=value} assignment. Returns false if it was rejected. */
    private boolean applyAssignment(Configuration config, String key, String value,
                                    boolean valuesOnly, boolean ignoreUnknown,
                                    PrintStream out, PrintStream err) {
        if (!key.startsWith(PREFIX)) {
            if (ignoreUnknown) {
                return true;
            }
            err.printf("sysctl: unknown variable '%s'%n", key);
            return false;
        }
        if (isReadOnly(key)) {
            err.printf("sysctl: %s: read-only variable%n", key);
            return false;
        }
        config.set(key, value);
        if (!valuesOnly) {
            out.printf("%s = %s%n", key, value);
        }
        return true;
    }

    /**
     * Loads {@code key = value} settings from a file. Blank lines and lines starting with
     * {@code #} or {@code ;} are ignored. Returns 1 if the file cannot be read or any entry is rejected.
     */
    private int loadFromFile(Path file, Configuration config, boolean valuesOnly, boolean ignoreUnknown,
                             PrintStream out, PrintStream err) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.printf("sysctl: cannot open \"%s\": %s%n", file, e.getMessage());
            return 1;
        }
        int exitCode = 0;
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                err.printf("sysctl: %s:%d: invalid syntax, continuing...%n", file, lineNo);
                exitCode = 1;
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!applyAssignment(config, key, value, valuesOnly, ignoreUnknown, out, err)) {
                exitCode = 1;
            }
        }
        return exitCode;
    }

    private boolean isReadOnly(String key) {
        return key.equals("noray.kernel.version") ||
               key.equals("noray.kernel.build_time") ||
               key.equals("noray.kernel.phase");
    }
}
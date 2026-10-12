package com.redactado.noray.command;

import com.redactado.noray.kernel.api.KernelContext;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Immutable context for a single command execution.
 * Mirrors the contract of a POSIX process: raw argv, parsed flags, environment, and I/O streams.
 * Supports pipeline I/O redirection and environment variable expansion.
 */
public final class CommandInvocation {

    private final String rawCommandLine;
    private final String commandName;
    private final List<String> args;
    private final Map<String, String> flags;
    private final Set<String> booleanFlags;
    private final Map<String, String> environment;
    private final int lastExitCode;
    private final KernelContext context;
    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;

    private CommandInvocation(Builder builder) {
        this.rawCommandLine = Objects.requireNonNull(builder.rawCommandLine, "rawCommandLine cannot be null");
        this.commandName = Objects.requireNonNull(builder.commandName, "commandName cannot be null");
        this.args = builder.args != null ? List.copyOf(builder.args) : List.of();
        this.flags = builder.flags != null ? Map.copyOf(builder.flags) : Map.of();
        this.booleanFlags = builder.booleanFlags != null ? Set.copyOf(builder.booleanFlags) : Set.of();
        this.environment = builder.environment != null ? Map.copyOf(builder.environment) : Map.of();
        this.lastExitCode = builder.lastExitCode;
        this.context = builder.context;
        this.in = builder.in != null ? builder.in : System.in;
        this.out = builder.out != null ? builder.out : System.out;
        this.err = builder.err != null ? builder.err : System.err;
    }

    public String rawCommandLine() { return rawCommandLine; }
    public String commandName() { return commandName; }
    public List<String> args() { return args; }
    public Map<String, String> flags() { return flags; }
    public Set<String> booleanFlags() { return booleanFlags; }
    public Map<String, String> environment() { return environment; }
    public int lastExitCode() { return lastExitCode; }
    public KernelContext context() { return context; }
    public InputStream in() { return in; }
    public PrintStream out() { return out; }
    public PrintStream err() { return err; }

    /** Returns true if a flag was set (boolean or key=value). */
    public boolean hasFlag(String flag) {
        return booleanFlags.contains(flag) || flags.containsKey(flag);
    }

    /** Returns the flag value, or {@code defaultValue} if not set. */
    public String getFlag(String flag, String defaultValue) {
        return flags.getOrDefault(flag, defaultValue);
    }

    /** Returns the environment variable value, or {@code defaultValue} if not set. */
    public String getEnv(String name, String defaultValue) {
        return environment.getOrDefault(name, defaultValue);
    }

    /** Returns the environment variable value, or null if not set. */
    public String getEnv(String name) {
        return environment.get(name);
    }

    /** Expands environment variables in a string (e.g., "$HOME" or "${HOME}"). */
    public String expandEnv(String value) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '$' && i + 1 < value.length()) {
                i++;
                char next = value.charAt(i);
                if (next == '{') {
                    int end = value.indexOf('}', i + 1);
                    if (end > i + 1) {
                        String varName = value.substring(i + 1, end);
                        String varValue = environment.get(varName);
                        if (varValue != null) result.append(varValue);
                        i = end + 1;
                        continue;
                    }
                } else if (Character.isLetter(next) || next == '_') {
                    int end = i + 1;
                    while (end < value.length() && (Character.isLetterOrDigit(value.charAt(end)) || value.charAt(end) == '_')) {
                        end++;
                    }
                    String varName = value.substring(i, end);
                    String varValue = environment.get(varName);
                    if (varValue != null) result.append(varValue);
                    i = end;
                    continue;
                }
                result.append('$');
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    /** Creates a new invocation with modified I/O streams (for pipeline support). */
    public CommandInvocation withStreams(InputStream in, PrintStream out, PrintStream err) {
        return new Builder(this)
                .in(in)
                .out(out)
                .err(err)
                .build();
    }

    /** Creates a new invocation with updated last exit code. */
    public CommandInvocation withLastExitCode(int exitCode) {
        return new Builder(this)
                .lastExitCode(exitCode)
                .build();
    }

    /** Creates a new invocation with additional environment variables. */
    public CommandInvocation withEnvironment(Map<String, String> additionalEnv) {
        Map<String, String> merged = new HashMap<>(environment);
        merged.putAll(additionalEnv);
        return new Builder(this)
                .environment(merged)
                .build();
    }

    // ── Builder ────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(CommandInvocation base) {
        return new Builder(base);
    }

    public static final class Builder {
        private String rawCommandLine;
        private String commandName;
        private List<String> args = List.of();
        private Map<String, String> flags = Map.of();
        private Set<String> booleanFlags = Set.of();
        private Map<String, String> environment = new HashMap<>();
        private int lastExitCode = 0;
        private KernelContext context;
        private InputStream in;
        private PrintStream out;
        private PrintStream err;

        private Builder() {
            this.environment.putAll(System.getenv());
        }

        private Builder(CommandInvocation base) {
            this.rawCommandLine = base.rawCommandLine;
            this.commandName = base.commandName;
            this.args = base.args;
            this.flags = base.flags;
            this.booleanFlags = base.booleanFlags;
            this.environment = new HashMap<>(base.environment);
            this.lastExitCode = base.lastExitCode;
            this.context = base.context;
            this.in = base.in;
            this.out = base.out;
            this.err = base.err;
        }

        public Builder rawCommandLine(String rawCommandLine) {
            this.rawCommandLine = rawCommandLine;
            return this;
        }

        public Builder commandName(String commandName) {
            this.commandName = commandName;
            return this;
        }

        public Builder args(List<String> args) {
            this.args = args;
            return this;
        }

        public Builder flags(Map<String, String> flags) {
            this.flags = flags;
            return this;
        }

        public Builder booleanFlags(Set<String> booleanFlags) {
            this.booleanFlags = booleanFlags;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment != null ? new HashMap<>(environment) : new HashMap<>();
            return this;
        }

        public Builder lastExitCode(int lastExitCode) {
            this.lastExitCode = lastExitCode;
            return this;
        }

        public Builder context(KernelContext context) {
            this.context = context;
            return this;
        }

        public Builder in(InputStream in) {
            this.in = in;
            return this;
        }

        public Builder out(PrintStream out) {
            this.out = out;
            return this;
        }

        public Builder err(PrintStream err) {
            this.err = err;
            return this;
        }

        public CommandInvocation build() {
            return new CommandInvocation(this);
        }
    }
}
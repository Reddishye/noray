package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.log.CircularLogJournal;
import com.redactado.noray.log.LogEntry;
import com.redactado.noray.log.LogLevel;

import java.util.List;
import java.util.Set;

public final class JournalCommand implements Command {

    private final CircularLogJournal journal;

    public JournalCommand(CircularLogJournal journal) {
        this.journal = journal;
    }

    @Override public Set<String> valueFlags() { return Set.of("n"); }

    @Override public Set<String> switchFlags() { return Set.of("json"); }

    @Override
    public String name() {
        return "journalctl";
    }

    @Override
    public String description() {
        return "Query and inspect kernel logs and journal entries";
    }

    @Override
    public List<String> aliases() {
        return List.of("logs", "dmesg");
    }

    @Override
    public String synopsis() {
        return "journalctl [-n <lines>] [--level DEBUG|INFO|WARN|ERROR] [--json]";
    }

    /** One JSON object per line (ts, level, source, msg, and exception when there is one), for log shippers. */
    static String toJson(LogEntry e) {
        StringBuilder sb = new StringBuilder("{\"ts\":\"").append(e.timestamp()).append("\",\"seq\":").append(e.sequence())
                .append(",\"level\":\"").append(e.level()).append("\",\"source\":\"").append(esc(e.source()))
                .append("\",\"msg\":\"").append(esc(e.message())).append('"');
        if (e.throwable() != null) {
            sb.append(",\"exception\":\"").append(esc(String.valueOf(e.throwable()))).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    @Override
    public int execute(CommandInvocation invocation) {
        int lines = 50;
        if (invocation.hasFlag("n")) {
            try {
                lines = Integer.parseInt(invocation.getFlag("n", "50"));
            } catch (NumberFormatException ignored) {}
        } else if (invocation.hasFlag("lines")) {
            try {
                lines = Integer.parseInt(invocation.getFlag("lines", "50"));
            } catch (NumberFormatException ignored) {}
        }

        LogLevel minLevel = LogLevel.DEBUG;
        if (invocation.hasFlag("level")) {
            try {
                minLevel = LogLevel.valueOf(invocation.getFlag("level", "DEBUG").toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        List<LogEntry> snapshot = journal.snapshot(lines, minLevel);
        if (snapshot.isEmpty()) {
            invocation.out().println("-- No entries --");
            return 0;
        }

        if (invocation.booleanFlags().contains("json")) {
            for (LogEntry entry : snapshot) {
                invocation.out().println(toJson(entry));
            }
            return 0;
        }

        for (LogEntry entry : snapshot) {
            invocation.out().printf("[%s] [%-5s] [%-16s] %s%n",
                    entry.timestamp(),
                    entry.level(),
                    entry.source(),
                    entry.message());
            if (entry.throwable() != null) {
                invocation.out().printf("    Exception: %s%n", entry.throwable().getMessage());
            }
        }
        return 0;
    }
}

package com.redactado.noray.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * One JSON object per line for log collectors (journald, Loki, Elasticsearch):
 * {@code {"ts":"…","level":"WARNING","logger":"noray.fs","thread":"…","msg":"…","error":"…"}}.
 * Messages and stack traces pass through {@link Redactor}.
 */
public final class JsonLogFormatter extends Formatter {

    @Override
    public String format(LogRecord r) {
        StringBuilder sb = new StringBuilder(256).append('{');
        field(sb, "ts", Instant.ofEpochMilli(r.getMillis()).toString()).append(',');
        field(sb, "level", r.getLevel().getName()).append(',');
        field(sb, "logger", r.getLoggerName() == null ? "" : r.getLoggerName()).append(',');
        field(sb, "thread", Thread.currentThread().getName()).append(',');
        field(sb, "msg", Redactor.redact(formatMessage(r)));
        if (r.getThrown() != null) {
            StringWriter w = new StringWriter();
            r.getThrown().printStackTrace(new PrintWriter(w));
            sb.append(',');
            field(sb, "error", Redactor.redact(w.toString()));
        }
        return sb.append("}\n").toString();
    }

    private static StringBuilder field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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
        return sb.append('"');
    }
}

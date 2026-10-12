package com.redactado.noray.log;

import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configures the JDK logging every module writes to through {@code System.Logger}:
 * {@code noray.log.format=json|text} (default text) and {@code noray.log.level} (JUL level name or TRACE/DEBUG/INFO/WARN/ERROR).
 */
public final class LogSetup {

    private LogSetup() {
    }

    public static void apply(Map<String, String> settings) {
        String format = settings.getOrDefault("noray.log.format", "text").trim().toLowerCase();
        Level level = level(settings.getOrDefault("noray.log.level", "INFO"));
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        if ("json".equals(format)) {
            for (Handler h : root.getHandlers()) {
                root.removeHandler(h);
            }
            ConsoleHandler h = new ConsoleHandler(); // stderr: journald keeps it apart from the shell's stdout
            h.setFormatter(new JsonLogFormatter());
            h.setLevel(level);
            root.addHandler(h);
        } else {
            for (Handler h : root.getHandlers()) {
                h.setLevel(level);
            }
        }
    }

    static Level level(String name) {
        return switch (name.trim().toUpperCase()) {
            case "TRACE", "FINEST" -> Level.FINEST;
            case "DEBUG", "FINE" -> Level.FINE;
            case "WARN", "WARNING" -> Level.WARNING;
            case "ERROR", "SEVERE" -> Level.SEVERE;
            case "OFF" -> Level.OFF;
            default -> Level.INFO;
        };
    }
}

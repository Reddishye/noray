package com.redactado.noray.log;

public enum LogLevel {
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40);

    private final int severity;

    LogLevel(int severity) {
        this.severity = severity;
    }

    public int getSeverity() {
        return severity;
    }

    public boolean includes(LogLevel other) {
        return this.severity <= other.severity;
    }
}

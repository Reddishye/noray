package com.redactado.noray.kernel.api;

public enum InitLevel {
    CORE(0),
    SUBSYSTEM(100),
    DRIVER(200),
    APPLICATION(300),
    LATE(400);

    private final int priority;

    InitLevel(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }
}
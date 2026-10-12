package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.kernel.core.NorayKernel;

import java.util.List;
import java.util.Objects;

public final class ShutdownCommand implements Command {

    private final Runnable shutdownAction;

    public ShutdownCommand(Runnable shutdownAction) {
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction cannot be null");
    }

    @Override
    public String name() {
        return "poweroff";
    }

    @Override
    public String description() {
        return "Halt and shut down the Noray Kernel gracefully";
    }

    @Override
    public List<String> aliases() {
        return List.of("shutdown", "exit", "quit");
    }

    @Override
    public int execute(CommandInvocation invocation) {
        invocation.out().println("Halting Noray Kernel...");
        Thread.ofVirtual().start(shutdownAction);
        return 0;
    }
}

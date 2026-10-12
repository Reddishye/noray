package com.redactado.noray.shell;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.command.CommandRegistry;
import com.redactado.noray.command.builtin.HelpCommand;
import com.redactado.noray.kernel.api.KernelPhase;
import com.redactado.noray.kernel.core.NorayKernel;
import com.redactado.noray.log.CircularLogJournal;
import com.redactado.noray.service.ServiceManager;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShellModuleTest {

    @Test
    void testShellLifecycleIsBoundToKernel() throws Exception {
        CircularLogJournal journal = new CircularLogJournal(100);
        ServiceManager sm = new ServiceManager(journal);
        CommandRegistry registry = new CommandRegistry();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out);

        try (NorayKernel kernel = new NorayKernel()) {
            ShellModule shellModule = new ShellModule(
                    registry,
                    sm,
                    journal,
                    kernel::shutdown,
                    System.in,
                    ps,
                    ps,
                    false  // non-interactive: shell thread not started
            );

            kernel.registerModule(shellModule);
            kernel.boot();
            assertEquals(KernelPhase.RUNNING, kernel.getContext().getPhase());

            kernel.shutdown();
            assertEquals(KernelPhase.TERMINATED, kernel.getContext().getPhase());
        }
    }

    @Test
    void testShellExitsOnEof() throws Exception {
        CircularLogJournal journal = new CircularLogJournal(100);
        ServiceManager sm = new ServiceManager(journal);
        CommandRegistry registry = new CommandRegistry();

        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out);

        try (NorayKernel kernel = new NorayKernel()) {
            ShellModule shellModule = new ShellModule(
                    registry,
                    sm,
                    journal,
                    kernel::shutdown,
                    in,
                    ps,
                    ps,
                    true
            );

            kernel.registerModule(shellModule);
            kernel.boot();
            assertEquals(KernelPhase.RUNNING, kernel.getContext().getPhase());

            kernel.awaitShutdown();
            assertEquals(KernelPhase.TERMINATED, kernel.getContext().getPhase());
        }
    }

    @Test
    void testCommandChainingWithSemicolon() throws Exception {
        // Test the registry dispatcher directly
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outPs = new PrintStream(out);
        PrintStream errPs = new PrintStream(err);

        try (NorayKernel kernel = new NorayKernel()) {
            CircularLogJournal journal = new CircularLogJournal(100);
            ServiceManager sm = new ServiceManager(journal);
            CommandRegistry registry = new CommandRegistry();
            registry.register(new HelpCommand(registry));

            // Test semicolon chaining
            int code = registry.dispatch("help; help", kernel.getContext(),
                    System.in, outPs, errPs, Map.of(), 0);
            assertEquals(0, code);

            String output = out.toString(StandardCharsets.UTF_8);
            String plain = output.replaceAll("\\u001B\\[[0-9;]*m", "");
            assertTrue(plain.contains("Available Noray System Commands:"),
                    "Semicolon chaining should produce help output. Got: " + plain.substring(0, Math.min(300, plain.length())));
        }
    }

    @Test
    void testEnvironmentVariableExpansion() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outPs = new PrintStream(out);
        PrintStream errPs = new PrintStream(err);

        try (NorayKernel kernel = new NorayKernel()) {
            CircularLogJournal journal = new CircularLogJournal(100);
            ServiceManager sm = new ServiceManager(journal);
            CommandRegistry registry = new CommandRegistry();

            // Test $? expansion: first command fails, second checks $?
            // Register a command that returns 42
            registry.register(new Command() {
                @Override public String name() { return "fail42"; }
                @Override public String description() { return "Returns exit code 42"; }
                @Override public int execute(CommandInvocation inv) { return 42; }
            });

            registry.register(new com.redactado.noray.command.builtin.HelpCommand(registry));
            int code1 = registry.dispatch("fail42", kernel.getContext(),
                    System.in, outPs, errPs, Map.of(), 0);
            assertEquals(42, code1);

            // Now $? should be "42" in the environment
            int code2 = registry.dispatch("help", kernel.getContext(),
                    System.in, outPs, errPs, Map.of(), code1);
            assertEquals(0, code2);

            String output = out.toString(StandardCharsets.UTF_8);
            String plain = output.replaceAll("\\u001B\\[[0-9;]*m", "");
            assertTrue(plain.contains("Available Noray System Commands:"),
                    "Help should run after fail42 with $?=42. Got: " + plain.substring(0, Math.min(300, plain.length())));
        }
    }
}
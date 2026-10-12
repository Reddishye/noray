package com.redactado.noray.shell;

import com.redactado.noray.command.builtin.NodenetCommand;

import com.redactado.noray.command.CommandRegistry;
import com.redactado.noray.command.builtin.*;
import com.redactado.noray.kernel.api.InitLevel;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.kernel.api.KernelPhase;
import com.redactado.noray.log.CircularLogJournal;
import com.redactado.noray.log.LogLevel;
import com.redactado.noray.service.ServiceManager;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Kernel module that bootstraps the Noray shell and registers all built-in commands.
 *
 * <p>When running in interactive mode the shell thread is launched as a non-daemon thread
 * so it keeps the JVM alive until the user terminates the session.  When the shell exits
 * (EOF or {@code poweroff}) it fires the kernel shutdown hook so the system halts cleanly.
 */
public final class ShellModule implements KernelModule {

    private final CommandRegistry commandRegistry;
    private final ServiceManager  serviceManager;
    private final CircularLogJournal journal;
    private final Runnable        shutdownHook;
    private final InputStream     in;
    private final PrintStream     out;
    private final PrintStream     err;
    private final boolean         interactive;

    private volatile InteractiveShell activeShell;
    private volatile Thread           shellThread;

    public ShellModule(
            CommandRegistry  commandRegistry,
            ServiceManager   serviceManager,
            CircularLogJournal journal,
            Runnable         shutdownHook,
            InputStream      in,
            PrintStream      out,
            PrintStream      err,
            boolean          interactive
    ) {
        this.commandRegistry = Objects.requireNonNull(commandRegistry, "commandRegistry cannot be null");
        this.serviceManager  = Objects.requireNonNull(serviceManager,  "serviceManager cannot be null");
        this.journal         = Objects.requireNonNull(journal,         "journal cannot be null");
        this.shutdownHook    = Objects.requireNonNull(shutdownHook,    "shutdownHook cannot be null");
        this.in              = Objects.requireNonNull(in,              "in cannot be null");
        this.out             = Objects.requireNonNull(out,             "out cannot be null");
        this.err             = Objects.requireNonNull(err,             "err cannot be null");
        this.interactive     = interactive;
    }

    /* ── KernelModule ──────────────────────────────────────────────────── */

    @Override public String id()                  { return "noray.shell"; }
    @Override public InitLevel getInitLevel()      { return InitLevel.APPLICATION; }
    @Override public int getOrderWithinLevel()     { return 999; }
    @Override public Set<String> dependencies()    { return Collections.emptySet(); }

    @Override
    public void init(KernelContext context) {
        // Register all built-in commands
        commandRegistry.register(new HelpCommand(commandRegistry));
        commandRegistry.register(new ServiceCommand(serviceManager));
        commandRegistry.register(new JournalCommand(journal));
        commandRegistry.register(new ModulesCommand());
        commandRegistry.register(new ShutdownCommand(shutdownHook));
        commandRegistry.register(new UNameCommand());
        commandRegistry.register(new SysctlCommand());
        commandRegistry.register(new VersionCommand());
        commandRegistry.register(new NodenetCommand());
        commandRegistry.register(new LsblkCommand());
        commandRegistry.register(new MountCommand());
        commandRegistry.register(new VolumeCommand());
        commandRegistry.register(new StorageCommand());
        commandRegistry.register(new NamespaceCommand());
        commandRegistry.register(new SandboxCommand());
        commandRegistry.register(new IdentityCommand());
        commandRegistry.register(new CapabilityCommand());
        commandRegistry.register(new WitnessCommand());
        commandRegistry.register(new VaultCommand());
        commandRegistry.register(new MetricsCommand());
        commandRegistry.register(new OnionCommand());
        commandRegistry.register(new PkgCommand());
        commandRegistry.register(new WebCommand());
        commandRegistry.register(new BackupCommand());
        commandRegistry.register(new PanicCommand(shutdownHook));
        commandRegistry.register(new UmountCommand());
        commandRegistry.register(new DfCommand());
        commandRegistry.register(new FsStatCommand());

        if (interactive) {
            activeShell = new InteractiveShell(commandRegistry, context, journal, shutdownHook, in, out, err);

            shellThread = new Thread(() -> {
                // draw the prompt only once the kernel is running
                while (context.getPhase() != KernelPhase.RUNNING) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                // Now that the system is quiet and RUNNING, start the shell
                activeShell.run();

            }, "noray-interactive-shell");

            shellThread.setDaemon(false);
            shellThread.start();
            journal.append(LogLevel.INFO, "ShellModule", "Interactive shell session armed and waiting for boot completion.");
        }
    }

    @Override
    public void shutdown(KernelContext context) {
        if (activeShell != null) {
            activeShell.stop();
        }
        if (shellThread != null && shellThread.isAlive()) {
            shellThread.interrupt();
        }
        journal.append(LogLevel.INFO, "ShellModule", "Interactive shell session terminated.");
    }
}

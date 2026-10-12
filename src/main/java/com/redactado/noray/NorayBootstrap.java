package com.redactado.noray;

import com.redactado.noray.command.CommandRegistry;
import com.redactado.noray.command.builtin.*;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelHook;
import com.redactado.noray.kernel.core.NorayKernel;
import com.redactado.noray.log.CircularLogJournal;
import com.redactado.noray.log.LogLevel;
import com.redactado.noray.service.RestartPolicy;
import com.redactado.noray.service.ServiceDescriptor;
import com.redactado.noray.service.ServiceManager;
import com.redactado.noray.shell.InteractiveShell;
import com.redactado.noray.shell.ShellModule;

import java.time.Duration;
import java.util.Map;

public final class NorayBootstrap {

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            if ("--help".equals(a) || "-h".equals(a)) {
                System.out.println("""
                        noray [--daemon] [--config FILE] [--set key=value]...
                          --daemon          no interactive shell (for services); stops cleanly on SIGTERM
                          --config FILE     properties file (else $NORAY_CONFIG, ./noray.properties, /etc/noray/noray.properties)
                          --set key=value   one setting, overrides the file and -Dnoray.* properties
                        """);
                return;
            }
        }
        Map<String, String> settings = NorayConfig.load(args);
        com.redactado.noray.log.LogSetup.apply(settings);
        CircularLogJournal journal = new CircularLogJournal(10_000);
        journal.append(LogLevel.INFO, "Bootstrap", "Initializing Noray OS Kernel runtime...");

        NorayKernel kernel = new NorayKernel(settings);

        int metricsPort = Integer.parseInt(settings.getOrDefault("noray.metrics.port", "0"));
        com.redactado.noray.kernel.metrics.MetricsHttpServer metricsServer = metricsPort > 0
                ? new com.redactado.noray.kernel.metrics.MetricsHttpServer(
                        com.redactado.noray.kernel.metrics.Metrics.global(), metricsPort)
                : null;
        // a service manager's SIGTERM must lead to the same clean stop as typing poweroff
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                kernel.shutdown();
            } catch (RuntimeException ignored) {
                // already stopping
            }
            if (metricsServer != null) metricsServer.close();
        }, "noray-shutdown"));

        // Connect kernel lifecycle events and errors to journal
        kernel.registerHook(new KernelHook() {
            @Override
            public void onBoot() {
                journal.append(LogLevel.INFO, "KernelHook", "Boot hook invoked.");
            }

            @Override
            public void onShutdown() {
                journal.append(LogLevel.INFO, "KernelHook", "Shutdown hook invoked.");
            }

            @Override
            public void onError(Throwable throwable, String context) {
                journal.append(LogLevel.ERROR, context, throwable.getMessage(), throwable);
            }
        });

        // Initialize Service Supervisor
        ServiceManager serviceManager = new ServiceManager(journal);
        kernel.registerModuleResource("noray.service-manager", serviceManager);

        // Register default system telemetry worker
        serviceManager.register(ServiceDescriptor.builder("telemetry.service", ctx -> {
            ctx.log("Telemetry daemon running.");
            while (!ctx.isStopRequested()) {
                Thread.sleep(5000);
                ctx.log("System health: NOMINAL (heap free: " +
                        (Runtime.getRuntime().freeMemory() / 1024 / 1024) + "MB)");
            }
        })
        .description("System diagnostics and metrics collection")
        .restartPolicy(RestartPolicy.ALWAYS)
        .restartDelay(Duration.ofSeconds(2))
        .build());

        // Register ShellModule directly into the Kernel as a managed KernelModule
        CommandRegistry commandRegistry = new CommandRegistry();
        boolean isDaemon = (args.length > 0 && "--daemon".equals(args[0]))
                || java.util.Arrays.asList(args).contains("--daemon")
                || Boolean.parseBoolean(System.getProperty("noray.daemon", "false"));
        boolean isInteractive = !isDaemon;
        ShellModule shellModule = new ShellModule(
                commandRegistry,
                serviceManager,
                journal,
                kernel::shutdown,
                System.in,
                System.out,
                System.err,
                isInteractive
        );
        kernel.registerModule(shellModule);

        // Discover external modules, then the operator-approved packages, and boot
        kernel.discoverModules();
        // The keyring is opened once, here, so package approvals can be authenticated before any package loads; the
        // network and storage modules reuse the registered instance instead of running Argon2 again.
        java.nio.file.Path dataDir = java.nio.file.Path.of(settings.getOrDefault("noray.data.dir", "data"));
        byte[] packageKey = null;
        try {
            com.redactado.noray.data.crypto.Keyring keyring =
                    com.redactado.noray.data.crypto.KeyringProvider.open(kernel.getContext(), dataDir);
            if (keyring != null) {
                kernel.getContext().getServiceRegistry().register(com.redactado.noray.data.crypto.Keyring.class, keyring);
                kernel.registerModuleResource("noray.bootstrap", keyring);
                packageKey = keyring.subKey("packages");
            }
        } catch (RuntimeException e) {
            journal.append(LogLevel.ERROR, "Bootstrap", "keyring not opened: " + e.getMessage());
        }
        com.redactado.noray.pkg.PackageManager packages = new com.redactado.noray.pkg.PackageManager(
                dataDir.resolve("packages"), packageKey);
        kernel.getContext().getServiceRegistry().register(com.redactado.noray.pkg.PackageManager.class, packages);
        kernel.getContext().getServiceRegistry().register(CircularLogJournal.class, journal);
        com.redactado.noray.pkg.PackageLoader.registerActive(kernel, packages);
        kernel.getContext().getServiceRegistry().register(com.redactado.noray.pkg.PackageRuntime.class,
                new com.redactado.noray.pkg.PackageRuntime(kernel, packages));
        kernel.boot();
        kernel.getContext().getServiceRegistry().get(com.redactado.noray.net.MeshTransport.class).ifPresent(mesh -> {
            var exchange = new com.redactado.noray.pkg.PackageExchange(mesh, packages);
            kernel.getContext().getServiceRegistry().register(com.redactado.noray.pkg.PackageExchange.class, exchange);
            kernel.registerModuleResource("noray.bootstrap", exchange);
        });

        // Auto-start base services
        serviceManager.startService("telemetry.service");

        // The main thread awaits kernel shutdown.
        kernel.awaitShutdown();
    }
}
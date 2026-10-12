package com.redactado.noray.kernel.core;

import com.redactado.noray.kernel.api.HealthCheck;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

public final class HealthWatchdog implements AutoCloseable {

    private static final long DEFAULT_CHECK_TIMEOUT_SECONDS = 5;

    private final Map<String, HealthCheck> checks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final BiConsumer<String, HealthCheck.HealthStatus> onFailure;
    private ScheduledFuture<?> scheduledTask;

    public HealthWatchdog(BiConsumer<String, HealthCheck.HealthStatus> onFailure) {
        this.onFailure = Objects.requireNonNull(onFailure, "Failure handler cannot be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "noray-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void registerCheck(HealthCheck check) {
        Objects.requireNonNull(check, "HealthCheck cannot be null");
        checks.put(check.name(), check);
    }

    public synchronized void start(long intervalAmount, TimeUnit unit) {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            return;
        }
        scheduledTask = scheduler.scheduleWithFixedDelay(this::runHealthChecks, intervalAmount, intervalAmount, unit);
    }

    public void runHealthChecks() {
        for (Map.Entry<String, HealthCheck> entry : checks.entrySet()) {
            String checkName = entry.getKey();
            HealthCheck check = entry.getValue();

            CompletableFuture<HealthCheck.HealthStatus> future = CompletableFuture.supplyAsync(check::check);
            try {
                HealthCheck.HealthStatus status = future.get(DEFAULT_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (status != null && !status.healthy()) {
                    onFailure.accept(checkName, status);
                }
            } catch (TimeoutException te) {
                future.cancel(true);
                onFailure.accept(checkName, HealthCheck.HealthStatus.unhealthy(
                        "Health check timed out after " + DEFAULT_CHECK_TIMEOUT_SECONDS + " seconds", te));
            } catch (Throwable t) {
                Throwable cause = (t instanceof ExecutionException && t.getCause() != null) ? t.getCause() : t;
                onFailure.accept(checkName, HealthCheck.HealthStatus.unhealthy("Watchdog check execution failed", cause));
            }
        }
    }

    @Override
    public synchronized void close() {
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
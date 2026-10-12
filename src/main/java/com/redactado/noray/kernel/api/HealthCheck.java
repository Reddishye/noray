package com.redactado.noray.kernel.api;

public interface HealthCheck {
    String name();
    HealthStatus check();

    record HealthStatus(boolean healthy, String details, Throwable cause) {
        public static HealthStatus ok(String details) {
            return new HealthStatus(true, details, null);
        }

        public static HealthStatus unhealthy(String details, Throwable cause) {
            return new HealthStatus(false, details, cause);
        }
    }
}
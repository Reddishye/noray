package com.redactado.noray.service;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

public record ServiceDescriptor(
        String name,
        String description,
        Set<String> dependencies,
        RestartPolicy restartPolicy,
        Duration restartDelay,
        int maxRestartRetries,
        ServiceWorker worker
) {
    public ServiceDescriptor {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(worker, "worker cannot be null");
        if (description == null) description = name;
        if (dependencies == null) dependencies = Collections.emptySet();
        if (restartPolicy == null) restartPolicy = RestartPolicy.NO;
        if (restartDelay == null) restartDelay = Duration.ofSeconds(1);
    }

    public static Builder builder(String name, ServiceWorker worker) {
        return new Builder(name, worker);
    }

    public static final class Builder {
        private final String name;
        private final ServiceWorker worker;
        private String description;
        private Set<String> dependencies = Collections.emptySet();
        private RestartPolicy restartPolicy = RestartPolicy.NO;
        private Duration restartDelay = Duration.ofSeconds(1);
        private int maxRestartRetries = 3;

        public Builder(String name, ServiceWorker worker) {
            this.name = name;
            this.worker = worker;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder dependencies(Set<String> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public Builder restartPolicy(RestartPolicy restartPolicy) {
            this.restartPolicy = restartPolicy;
            return this;
        }

        public Builder restartDelay(Duration delay) {
            this.restartDelay = delay;
            return this;
        }

        public Builder maxRestartRetries(int retries) {
            this.maxRestartRetries = retries;
            return this;
        }

        public ServiceDescriptor build() {
            return new ServiceDescriptor(name, description, dependencies, restartPolicy, restartDelay, maxRestartRetries, worker);
        }
    }
}

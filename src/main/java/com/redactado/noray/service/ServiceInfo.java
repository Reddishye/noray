package com.redactado.noray.service;

import java.time.Instant;

public record ServiceInfo(
        String name,
        String description,
        ServiceStatus status,
        int restartCount,
        Instant startedAt,
        String lastError
) {}

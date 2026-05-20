package com.jake.realtimeapi.events.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProcessEventResult(
        UUID idempotencyKey,
        boolean replayed,
        Instant processedAt
) {
    public ProcessEventResult {
        if (idempotencyKey == null) {
            throw new NullPointerException("idempotencyKey is required");
        }
        if (processedAt == null) {
            throw new NullPointerException("processedAt is required");
        }
    }
}

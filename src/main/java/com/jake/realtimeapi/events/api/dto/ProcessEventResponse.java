package com.jake.realtimeapi.events.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ProcessEventResponse(
        UUID idempotencyKey,
        boolean replayed,
        Instant processedAt
) {
}

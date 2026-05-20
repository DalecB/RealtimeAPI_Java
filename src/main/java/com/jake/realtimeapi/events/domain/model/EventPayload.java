package com.jake.realtimeapi.events.domain.model;

import java.util.UUID;

public record EventPayload(
        UUID leaderboardId,
        long userId,
        long deltaScore,
        UUID idempotencyKey
) {
    public EventPayload {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }

    public String payloadHashSource() {
        return userId + ":" + deltaScore;
    }
}

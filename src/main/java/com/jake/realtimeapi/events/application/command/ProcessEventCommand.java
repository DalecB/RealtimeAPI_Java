package com.jake.realtimeapi.events.application.command;

import java.util.UUID;

public record ProcessEventCommand(
        UUID leaderboardId,
        long userId,
        long deltaScore,
        UUID idempotencyKey
) {
    public ProcessEventCommand {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (deltaScore <= 0) {
            throw new IllegalArgumentException("deltaScore must be positive");
        }
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }
}

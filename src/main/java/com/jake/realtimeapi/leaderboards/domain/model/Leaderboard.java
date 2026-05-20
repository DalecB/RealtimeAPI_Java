package com.jake.realtimeapi.leaderboards.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Leaderboard(
        UUID id,
        UUID projectId,
        String name,
        Instant createdAt
) {

    private static final int MAX_NAME_LENGTH  = 255;

    public Leaderboard {
        if(projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        if(name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if(name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name length must be <= " + MAX_NAME_LENGTH);
        }
    }

    public static Leaderboard newLeaderboard(UUID projectId, String name) {
        return new Leaderboard(null, projectId, name, null);
    }
}

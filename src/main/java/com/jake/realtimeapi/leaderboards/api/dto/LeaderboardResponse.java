package com.jake.realtimeapi.leaderboards.api.dto;

import java.time.Instant;
import java.util.UUID;

public record LeaderboardResponse(
        UUID id,
        UUID projectId,
        String name,
        Instant createdAt
) {
}

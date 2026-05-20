package com.jake.realtimeapi.events.application.query;

import java.util.UUID;

public record GetUserRankQuery(
        UUID leaderboardId,
        long userId
) {

    public GetUserRankQuery {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
    }
}

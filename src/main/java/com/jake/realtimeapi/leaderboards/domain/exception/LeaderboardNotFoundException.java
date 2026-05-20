package com.jake.realtimeapi.leaderboards.domain.exception;

import java.util.UUID;

public class LeaderboardNotFoundException extends RuntimeException {

    public LeaderboardNotFoundException(UUID id) {
        super("Leaderboard not found: id=" + id);
    }

    public LeaderboardNotFoundException(UUID projectId, String name) {
        super("Leaderboard not found: projectId=" + projectId + " name=" + name);
    }
}

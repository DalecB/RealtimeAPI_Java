package com.jake.realtimeapi.leaderboards.domain.exception;

import java.util.UUID;

public class LeaderboardAlreadyExistsException extends RuntimeException {

    public LeaderboardAlreadyExistsException(UUID projectId, String name) {
        super("Leaderboard with name " + name + " already exists! [projectId="  + projectId + "]");
    }
}

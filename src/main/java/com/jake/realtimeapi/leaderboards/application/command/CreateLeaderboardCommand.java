package com.jake.realtimeapi.leaderboards.application.command;

import java.util.UUID;

public record CreateLeaderboardCommand(UUID projectId, String name, Long requesterAdminId) {

    public CreateLeaderboardCommand {
        if(projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        if(name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (requesterAdminId == null || requesterAdminId <= 0) {
            throw new IllegalArgumentException("requesterAdminId is required");
        }
    }
}

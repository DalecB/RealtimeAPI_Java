package com.jake.realtimeapi.leaderboards.api.dto;

import java.util.List;

public record ListLeaderboardResponse(
        List<LeaderboardResponse> items,
        int returnedCount
) {
}

package com.jake.realtimeapi.leaderboards.api.dto;

public record UserRankResponse(
        String userId,
        long score,
        Integer rank
) {
}

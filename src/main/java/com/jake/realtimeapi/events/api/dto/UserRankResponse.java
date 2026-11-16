package com.jake.realtimeapi.events.api.dto;

public record UserRankResponse(
        String userId,
        long score,
        Integer rank
) {
}

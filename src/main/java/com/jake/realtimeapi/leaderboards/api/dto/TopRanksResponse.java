package com.jake.realtimeapi.leaderboards.api.dto;

import java.util.List;
import java.util.UUID;

public record TopRanksResponse(
        UUID leaderboardId,
        List<TopRankItemResponse> items,
        long total
) {
    public record TopRankItemResponse(
            int rank,
            String userId,
            long score
    ) {
    }
}

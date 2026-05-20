package com.jake.realtimeapi.events.domain.model;

import java.util.List;
import java.util.UUID;

public record TopRanksResult(
        UUID leaderboardId,
        List<TopRankItem> items,
        long total
) {
    public TopRanksResult {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
    }
}

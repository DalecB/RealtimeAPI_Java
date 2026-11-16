package com.jake.realtimeapi.events.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TopRanksResult(
        UUID leaderboardId,
        List<TopRankItem> items,
        // 스냅샷 fallback에서는 전체 참여자 수를 모르므로 null이다(스냅샷은 Top-N만 저장).
        Long total,
        RankingSource source,
        // source가 SNAPSHOT일 때 스냅샷 시각, REDIS면 null
        Instant asOf
) {
    public TopRanksResult(UUID leaderboardId, List<TopRankItem> items, Long total) {
        this(leaderboardId, items, total, RankingSource.REDIS, null);
    }

    public TopRanksResult {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
    }
}

package com.jake.realtimeapi.events.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TopRanksResponse(
        UUID leaderboardId,
        List<TopRankItemResponse> items,
        // 스냅샷 fallback이고 Top-N이 잘려 있으면 전체 참여자 수를 몰라 null
        Long total,
        // REDIS | SNAPSHOT
        String source,
        // source가 SNAPSHOT일 때 스냅샷 시각
        Instant asOf
) {
    public record TopRankItemResponse(
            int rank,
            String userId,
            long score
    ) {
    }
}

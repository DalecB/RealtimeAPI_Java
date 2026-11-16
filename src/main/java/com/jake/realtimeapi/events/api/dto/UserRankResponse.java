package com.jake.realtimeapi.events.api.dto;

import java.time.Instant;

public record UserRankResponse(
        String userId,
        long score,
        Integer rank,
        // REDIS | SNAPSHOT
        String source,
        // source가 SNAPSHOT일 때 스냅샷 시각
        Instant asOf
) {
}

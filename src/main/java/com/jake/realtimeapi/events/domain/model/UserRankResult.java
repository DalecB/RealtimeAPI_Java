package com.jake.realtimeapi.events.domain.model;

import java.time.Instant;

public record UserRankResult(
        long userId,
        long score,
        Integer rank,
        RankingSource source,
        // source가 SNAPSHOT일 때 스냅샷 시각, REDIS면 null
        Instant asOf
) {

    public UserRankResult(long userId, long score, Integer rank) {
        this(userId, score, rank, RankingSource.REDIS, null);
    }

    public UserRankResult {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (score < 0) {
            throw new IllegalArgumentException("score cannot be negative");
        }
        if (rank != null && rank <= 0) {
            throw new IllegalArgumentException("rank must be positive when provided");
        }
        if (rank == null && score != 0) {
            throw new IllegalArgumentException("rank can be null only when score is 0");
        }
    }
}

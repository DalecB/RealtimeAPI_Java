package com.jake.realtimeapi.events.domain.model;

public record UserRankResult(
        long userId,
        long score,
        Integer rank
) {

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

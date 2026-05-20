package com.jake.realtimeapi.events.domain.model;

public record TopRankItem(
        int rank,
        long userId,
        long score
) {
    public TopRankItem {
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (score < 0) {
            throw new IllegalArgumentException("score cannot be negative");
        }
    }
}

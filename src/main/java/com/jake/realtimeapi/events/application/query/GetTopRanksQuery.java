package com.jake.realtimeapi.events.application.query;

import java.util.UUID;

public record GetTopRanksQuery(
        UUID leaderboardId,
        int offset,
        int limit
) {

    public static final int MIN_OFFSET = 0;
    public static final int MAX_OFFSET = 9_999;

    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 100;

    public GetTopRanksQuery {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
        if (offset < MIN_OFFSET || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("offset must be between 0 and 9999, but was " + offset);
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 100, but was " + limit);
        }
    }
}

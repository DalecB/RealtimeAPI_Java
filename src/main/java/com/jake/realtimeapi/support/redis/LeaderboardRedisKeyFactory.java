package com.jake.realtimeapi.support.redis;

import java.util.UUID;

public final class LeaderboardRedisKeyFactory {

    private LeaderboardRedisKeyFactory() {}

    public static String rankingKey(UUID leaderboardId) {
        return "lb:{" + require(leaderboardId, "leaderboardId") + "}:z";
    }

    public static String idempotencyKey(UUID leaderboardId, UUID eventUuid) {
        return "lb:{" + require(leaderboardId, "leaderboardId") + "}:idem:" + require(eventUuid, "eventUuid");
    }

    public static String auditStreamKey(UUID leaderboardId) {
        return "lb:{" + require(leaderboardId, "leaderboardId") + "}:events";
    }

    private static Object require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

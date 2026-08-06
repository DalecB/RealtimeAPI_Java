package com.jake.realtimeapi.support.redis;

import java.util.UUID;

public final class LeaderboardRedisKeyFactory {

    /** audit 스트림을 Kafka로 옮기는 relay 컨슈머 그룹. relay·트림·상태 조회가 공유한다. */
    public static final String AUDIT_RELAY_GROUP = "audit-relay";

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

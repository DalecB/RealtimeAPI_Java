package com.jake.realtimeapi.events.persistence.redis;

import java.util.UUID;

public final class LeaderboardRedisKeyFactory {

    public static final String AUDIT_RELAY_GROUP = "audit-relay";

    private LeaderboardRedisKeyFactory() {}

    public static String rankingKey(UUID leaderboardId) {
        return "lb:{" + require(leaderboardId, "leaderboardId") + "}:z";
    }

    public static String idempotencyKey(UUID leaderboardId, UUID eventUuid) {
        return "lb:{" + require(leaderboardId, "leaderboardId") + "}:idem:" + require(eventUuid, "eventUuid");
    }

    /**
     * 이 Redis의 랭킹 데이터가 온전하다는 표식. 랭킹과 같은 Redis에 있으므로 데이터가 유실되면 같이 사라진다.
     * 없으면 cold: 쓰기는 거부하고 읽기는 스냅샷으로 답하며, 스냅샷 워커가 복구한 뒤 다시 쓴다.
     */
    public static String warmKey(UUID leaderboardId) {
        return "lb:{" + require(leaderboardId, "leaderboardId") + "}:warm";
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

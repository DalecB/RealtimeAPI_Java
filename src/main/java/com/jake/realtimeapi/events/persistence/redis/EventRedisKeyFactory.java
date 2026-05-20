package com.jake.realtimeapi.events.persistence.redis;

import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;

import java.util.UUID;

public final class EventRedisKeyFactory {

    private EventRedisKeyFactory() {}

    public static String rankingKey(UUID leaderboardId) {
        return LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
    }

    public static String idempotencyKey(UUID leaderboardId, UUID eventUuid) {
        return LeaderboardRedisKeyFactory.idempotencyKey(leaderboardId, eventUuid);
    }

    public static String auditStreamKey(UUID leaderboardId) {
        return LeaderboardRedisKeyFactory.auditStreamKey(leaderboardId);
    }
}

package com.jake.realtimeapi.events.persistence.redis;

import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.events.domain.model.StreamsStatus;
import com.jake.realtimeapi.events.domain.repository.AuditStreamStatusRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class RedisAuditStreamStatusRepository implements AuditStreamStatusRepository {

    private final StringRedisTemplate redisTemplate;

    public RedisAuditStreamStatusRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public StreamsStatus getStatus(UUID leaderboardId) {
        String key = LeaderboardRedisKeyFactory.auditStreamKey(leaderboardId);
        Long streamLength = redisTemplate.opsForStream().size(key);
        long safeLength = streamLength == null ? 0L : streamLength;

        if (safeLength == 0L) {
            return new StreamsStatus(0L, 0L);
        }

        return new StreamsStatus(pendingCount(key), safeLength);
    }

    private long pendingCount(String key) {
        // relay 컨슈머 그룹의 미처리(PEL) 크기 = relay가 받았지만 아직 Kafka로 못 옮긴 건수.
        // relay가 밀리거나 멈추면 이 값이 자란다 → relay 건강 지표.
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                    .pending(key, LeaderboardRedisKeyFactory.AUDIT_RELAY_GROUP);
            return summary == null ? 0L : summary.getTotalPendingMessages();
        } catch (DataAccessException ex) {
            // relay가 아직 그룹을 안 만들었으면(비활성/미기동) 대기 0으로 본다.
            return 0L;
        }
    }
}

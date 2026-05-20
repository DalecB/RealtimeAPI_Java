package com.jake.realtimeapi.events.persistence.redis;

import com.jake.realtimeapi.events.domain.model.StreamsStatus;
import com.jake.realtimeapi.events.domain.repository.AuditStreamStatusRepository;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class RedisAuditStreamStatusRepository implements AuditStreamStatusRepository {

    private final StringRedisTemplate redisTemplate;

    public RedisAuditStreamStatusRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public StreamsStatus getStatus(UUID leaderboardId) {
        String key = EventRedisKeyFactory.auditStreamKey(leaderboardId);
        Long streamLength = redisTemplate.opsForStream().size(key);
        long safeLength = streamLength == null ? 0L : streamLength;

        if (safeLength == 0L) {
            return new StreamsStatus(0L, 0L, null);
        }

        List<MapRecord<String, Object, Object>> lastRecords = redisTemplate.opsForStream()
                .reverseRange(key, Range.unbounded(), Limit.limit().count(1));
        String lastDeliveredId = lastRecords.isEmpty() ? null : lastRecords.get(0).getId().getValue();

        // The current implementation only appends audit events and does not run a dedicated consumer group yet.
        // Until a real stream consumer exists, pending entries stay 0 and consumer lag is approximated by stream length.
        return new StreamsStatus(0L, safeLength, lastDeliveredId);
    }
}

package com.jake.realtimeapi.events.persistence.redis;

import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.events.domain.model.StreamsStatus;
import com.jake.realtimeapi.events.domain.repository.AuditStreamStatusRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
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
            return new StreamsStatus(0L, 0L, 0L);
        }

        return new StreamsStatus(pendingCount(key), safeLength, consumerGroupLag(key, safeLength));
    }

    private long pendingCount(String key) {
        // 릴레이가 읽었지만 아직 XACK하지 않은 PEL 크기. 읽기 전 적체는 아래 group lag으로 본다.
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                    .pending(key, LeaderboardRedisKeyFactory.AUDIT_RELAY_GROUP);
            return summary == null ? 0L : summary.getTotalPendingMessages();
        } catch (DataAccessException ex) {
            // relay가 아직 그룹을 안 만들었으면(비활성/미기동) 대기 0으로 본다.
            return 0L;
        }
    }

    private long consumerGroupLag(String key, long streamLength) {
        try {
            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(key);
            for (StreamInfo.XInfoGroup group : groups) {
                if (LeaderboardRedisKeyFactory.AUDIT_RELAY_GROUP.equals(group.groupName())) {
                    Object lag = group.getRaw().get("lag");
                    if (lag instanceof Number number) {
                        return number.longValue();
                    }
                    return lag == null ? streamLength : Long.parseLong(lag.toString());
                }
            }
        } catch (DataAccessException ex) {
            // 그룹이 없으면 현재 스트림 전체가 아직 릴레이에 전달되지 않은 상태다.
        }
        return streamLength;
    }
}

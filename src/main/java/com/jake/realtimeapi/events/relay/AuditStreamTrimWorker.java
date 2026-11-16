package com.jake.realtimeapi.events.relay;

import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

// XACK은 PEL만 비우고 스트림 본문은 남기므로 옮긴 구간을 따로 잘라야 한다.
@Component
@ConditionalOnProperty(name = "events.relay.enabled", havingValue = "true")
public class AuditStreamTrimWorker {

    private static final Logger log = LoggerFactory.getLogger(AuditStreamTrimWorker.class);

    private final LeaderboardRepository leaderboardRepository;
    private final StringRedisTemplate redisTemplate;
    private final AuditRelayLeaderLock leaderLock;

    public AuditStreamTrimWorker(
            LeaderboardRepository leaderboardRepository,
            StringRedisTemplate redisTemplate,
            AuditRelayLeaderLock leaderLock
    ) {
        this.leaderboardRepository = leaderboardRepository;
        this.redisTemplate = redisTemplate;
        this.leaderLock = leaderLock;
    }

    @Scheduled(fixedDelayString = "${events.relay.trim-delay-ms:40000}")
    public void trimAll() {
        // XPENDING과 XINFO 사이에 릴레이가 새로 읽으면 미XACK 구간이 잘리므로, 리더에서만 돌려 릴레이와 같은 스케줄러 스레드로 직렬화한다.
        if (!leaderLock.isLeader()) {
            return;
        }
        for (UUID leaderboardId : leaderboardRepository.findAllIds()) {
            try {
                trimOne(leaderboardId);
            } catch (DataAccessException ex) {
                log.error("trim failed leaderboardId={}", leaderboardId, ex);
            }
        }
    }

    private void trimOne(UUID leaderboardId) {
        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(leaderboardId);
        String watermark = watermark(streamKey);
        if (watermark == null) {
            return;
        }
        // MINID는 watermark 미만만 지우므로 watermark인 가장 오래된 미XACK 엔트리는 잘리지 않는다.
        Long removed = redisTemplate.execute((RedisCallback<Long>) connection -> {
            Object reply = connection.execute(
                    "XTRIM",
                    streamKey.getBytes(StandardCharsets.UTF_8),
                    "MINID".getBytes(StandardCharsets.UTF_8),
                    watermark.getBytes(StandardCharsets.UTF_8));
            return reply == null ? 0L : ((Number) reply).longValue();
        });
        if (removed != null && removed > 0) {
            log.debug("trim streamKey={} removed={} watermark={}", streamKey, removed, watermark);
        }
    }

    // PEL이 비었으면 배달분이 모두 XACK된 것이라 그룹이 마지막으로 받아간 지점까지 자를 수 있다.
    private String watermark(String streamKey) {
        PendingMessagesSummary pending;
        try {
            pending = redisTemplate.opsForStream().pending(streamKey, AuditRelayWorker.GROUP);
        } catch (DataAccessException ex) {
            // 그룹·스트림이 없으면 XPENDING이 실패한다. 옮긴 게 없으니 자르지 않는다.
            return null;
        }

        if (pending != null && pending.getTotalPendingMessages() > 0) {
            return pending.minMessageId();
        }
        return lastDeliveredId(streamKey);
    }

    private String lastDeliveredId(String streamKey) {
        StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(streamKey);
        for (StreamInfo.XInfoGroup group : groups) {
            if (AuditRelayWorker.GROUP.equals(group.groupName())) {
                return group.lastDeliveredId();
            }
        }
        return null;
    }
}

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
        // 워터마크는 어느 인스턴스에서 읽어도 안전하지만 여러 인스턴스가 같은 스트림을 자를 이유가 없어 리더에서만 돈다.
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

    private String watermark(String streamKey) {
        String lastDelivered;
        PendingMessagesSummary pending;
        try {
            // last-delivered를 XPENDING보다 먼저 읽어야 한다. 그 이하는 이미 배달됐으니 뒤이어 본 PEL이 비면 모두 XACK된 것이고, 반대 순서면 사이에 릴레이가 읽은 미XACK 구간까지 자른다.
            lastDelivered = lastDeliveredId(streamKey);
            if (lastDelivered == null) {
                return null;
            }
            pending = redisTemplate.opsForStream().pending(streamKey, AuditRelayWorker.GROUP);
        } catch (DataAccessException ex) {
            // 스트림이 없으면 XINFO GROUPS가 실패한다. 옮긴 게 없으니 자르지 않는다.
            return null;
        }

        if (pending != null && pending.getTotalPendingMessages() > 0) {
            return pending.minMessageId();
        }
        return lastDelivered;
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

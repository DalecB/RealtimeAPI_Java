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

/**
 * relay가 Kafka로 옮긴 audit 기록을 Redis outbox 스트림에서 지운다.
 *
 * <p>XACK은 PEL만 비우고 스트림 본문은 남기므로, relay가 아무리 옮겨도 본문은 계속 쌓인다.
 * 이 워커가 "모두가 XACK 끝낸 구간"을 잘라 메모리를 돌려준다. relay와 별도 주기로 돈다.
 *
 * <p>워터마크 원칙: <b>확신할 수 있을 때만 자르고, 확신 없으면 안 자른다.</b> 트림은 데이터를
 * 지우기만 하므로 중복은 못 만들지만, 아직 옮기지 못한 것을 지우면 유실이 된다. 그래서 워터마크는
 * 옮김이 끝난 지점을 절대 넘지 않는다.
 */
@Component
@ConditionalOnProperty(name = "events.relay.enabled", havingValue = "true")
public class AuditStreamTrimWorker {

    private static final Logger log = LoggerFactory.getLogger(AuditStreamTrimWorker.class);

    private final LeaderboardRepository leaderboardRepository;
    private final StringRedisTemplate redisTemplate;

    public AuditStreamTrimWorker(LeaderboardRepository leaderboardRepository, StringRedisTemplate redisTemplate) {
        this.leaderboardRepository = leaderboardRepository;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelayString = "${events.relay.trim-delay-ms:40000}")
    public void trimAll() {
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
            return; // 자를 근거가 없으면 건너뛴다(그룹 없음 등). 다음 틱에 다시 본다.
        }
        // MINID: 이 id보다 오래된 엔트리를 지운다. watermark 자신과 그 이후는 남는다.
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

    /**
     * 안전하게 자를 수 있는 최대 지점(=이 id 앞을 지워도 되는 경계)을 구한다. 근거가 없으면 null.
     *
     * <ul>
     *   <li>미처리(PEL) 있음 → 가장 오래된 미처리 <b>앞까지만</b> 안전하다. 그 뒤는 아직 XACK 안 됐다.
     *   <li>미처리 없음 → 배달된 게 전부 XACK됐다는 뜻이므로 그룹이 마지막으로 받아간 지점까지 안전하다.
     *   <li>그룹 없음 → 아무것도 옮겨지지 않았다. 자를 근거가 없으므로 null.
     * </ul>
     */
    private String watermark(String streamKey) {
        PendingMessagesSummary pending;
        try {
            pending = redisTemplate.opsForStream().pending(streamKey, AuditRelayWorker.GROUP);
        } catch (DataAccessException ex) {
            // 그룹이 없거나 스트림이 없으면 여기서 걸린다 → 자를 근거 없음.
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

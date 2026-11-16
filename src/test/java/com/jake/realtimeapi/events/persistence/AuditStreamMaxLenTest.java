package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.events.domain.repository.RankingWarmthRepository;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// events.relay.enabled 기본값 false라 릴레이가 그룹을 만들지도 읽지도 않는다. 장시간 중단과 같은 조건이다.
@SpringBootTest(properties = "events.audit-stream.max-len=" + AuditStreamMaxLenTest.MAX_LEN)
@Import(TestcontainersConfiguration.class)
class AuditStreamMaxLenTest {

    static final int MAX_LEN = 100;
    private static final int OVERFLOW_TOTAL = MAX_LEN * 3 + 50;
    private static final long API_KEY_ID = 1L;

    @Autowired
    private EventCommandRepository eventCommandRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 실제로는 리더보드 생성(POST /leaderboards)이 쓰는 키다. 이 테스트는 리더보드를 직접 만들므로 직접 쓴다.
    @Autowired
    private RankingWarmthRepository rankingWarmthRepository;

    @Test
    void process_keepsEveryUnrelayedEntryUpToMaxLen() {
        UUID leaderboardId = UUID.randomUUID();
        rankingWarmthRepository.markWarm(leaderboardId);

        List<String> sent = sendEvents(leaderboardId, MAX_LEN);

        assertThat(idempotencyKeysInStream(leaderboardId)).containsExactlyElementsOf(sent);
    }

    // MAXLEN ~는 radix 노드 단위로만 자르므로 정확히 MAX_LEN으로 맞춰지지 않는다. 하한과 최신 구간 보존만 고정한다.
    @Test
    void process_trimsOldestUnrelayedEntriesBeyondMaxLen() {
        UUID leaderboardId = UUID.randomUUID();
        rankingWarmthRepository.markWarm(leaderboardId);

        List<String> sent = sendEvents(leaderboardId, OVERFLOW_TOTAL);

        List<String> kept = idempotencyKeysInStream(leaderboardId);
        assertThat(kept.size()).isGreaterThanOrEqualTo(MAX_LEN).isLessThan(OVERFLOW_TOTAL);
        assertThat(kept).containsExactlyElementsOf(sent.subList(OVERFLOW_TOTAL - kept.size(), OVERFLOW_TOTAL));
    }

    private List<String> sendEvents(UUID leaderboardId, int count) {
        List<String> idempotencyKeys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID idempotencyKey = UUID.randomUUID();
            eventCommandRepository.process(new EventPayload(leaderboardId, i + 1L, 1L, idempotencyKey), API_KEY_ID);
            idempotencyKeys.add(idempotencyKey.toString());
        }
        return idempotencyKeys;
    }

    private List<String> idempotencyKeysInStream(UUID leaderboardId) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(LeaderboardRedisKeyFactory.auditStreamKey(leaderboardId), Range.unbounded());
        return records.stream()
                .map(record -> (String) record.getValue().get("idempotencyKey"))
                .toList();
    }
}

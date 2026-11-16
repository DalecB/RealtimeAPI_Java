package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.domain.exception.IdempotencyKeyReuseMismatchException;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.model.ProcessEventResult;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProcessEventConcurrencyTest {

    private static final int THREAD_COUNT = 50;
    private static final long DELTA_SCORE = 10L;
    private static final long USER_ID = 1L;
    private static final long API_KEY_ID = 1L;

    @Autowired
    private EventCommandRepository eventCommandRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(THREAD_COUNT);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void process_appliesScoreOnceAndReplays49When50ConcurrentRequestsShareSameKeyAndPayload() throws Exception {
        UUID leaderboardId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        EventPayload payload = new EventPayload(leaderboardId, USER_ID, DELTA_SCORE, idempotencyKey);

        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch endGate = new CountDownLatch(THREAD_COUNT);
        List<ProcessEventResult> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try{
                    startGate.await();
                    results.add(eventCommandRepository.process(payload, API_KEY_ID));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        endGate.await();

        int newCount = 0;
        int replayCount = 0;
        int exceptionCount = 0;

        for (ProcessEventResult result : results) {
            if(result.replayed()) {
               replayCount++;
            } else {
                newCount++;
            }
        }

        exceptionCount = THREAD_COUNT - (replayCount + newCount);

        String rankingKey = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
        String auditStreamKey = LeaderboardRedisKeyFactory.auditStreamKey(leaderboardId);
        String member = UserIdCodec.format(payload.userId());

        Double score = redisTemplate.opsForZSet().score(rankingKey, member);
        Long xLen = redisTemplate.opsForStream().size(auditStreamKey);

        assertEquals(1, newCount);
        assertEquals(49, replayCount);
        assertEquals(0, exceptionCount);
        assertEquals(DELTA_SCORE, score);
        assertEquals(1, xLen);
    }

    @Test
    void process_throwsMismatchWhenSameKeyIsReusedWithDifferentPayload() {
        UUID leaderboardId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        EventPayload payload1 = new EventPayload(leaderboardId, USER_ID, DELTA_SCORE, idempotencyKey);
        eventCommandRepository.process(payload1, API_KEY_ID);

        final EventPayload payload2 = new EventPayload(leaderboardId, USER_ID, DELTA_SCORE + 3, idempotencyKey);
        assertThrows(IdempotencyKeyReuseMismatchException.class, () -> eventCommandRepository.process(payload2, API_KEY_ID));

        String rankingKey = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
        String member = UserIdCodec.format(payload1.userId());

        Double score = redisTemplate.opsForZSet().score(rankingKey, member);
        assertEquals(DELTA_SCORE, score);

        String auditStreamKey = LeaderboardRedisKeyFactory.auditStreamKey(leaderboardId);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(auditStreamKey, Range.unbounded());

        assertEquals(2, records.size());
        assertEquals("new", records.get(0).getValue().get("type"));

        Map<Object, Object> conflict = records.get(1).getValue();
        assertEquals("conflict", conflict.get("type"));
        // conflict의 delta는 반영값이 아니라 거부된 값이어야 이상 탐지가 무엇을 시도했는지 본다.
        assertEquals(Long.toString(DELTA_SCORE + 3), conflict.get("delta"));
        assertEquals(idempotencyKey.toString(), conflict.get("idempotencyKey"));
        assertEquals(Long.toString(API_KEY_ID), conflict.get("apiKeyId"));
    }
}

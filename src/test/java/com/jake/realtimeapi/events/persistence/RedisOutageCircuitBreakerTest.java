package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.infra.circuitbreaker.CircuitBreakerStatus;
import com.jake.realtimeapi.infra.circuitbreaker.RedisCircuitBreakerOpenException;
import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * verify-circuit-breaker-open.sh의 JUnit 자동화판.
 * Redis가 죽으면 실패가 누적돼 브레이커가 열리고(OPEN),
 * 열린 뒤의 요청은 Redis를 기다리지 않고 즉시 실패(fail-fast)해야 한다.
 * 어댑터 레벨 검증 — 브레이커는 JVM 안(어댑터의 Lua 호출을 감싸는 지점)에 산다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
// 이 테스트는 Redis 컨테이너를 죽인 채 끝나므로, 캐시된 컨텍스트를 다음 테스트 클래스에 물려주면 안 된다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisOutageCircuitBreakerTest {

    // 웜 윈도우 시나리오(ⓐ′): 성공 10건으로 창을 채운 뒤 Redis 사망 →
    // 실패 k건이면 실패율 k/10, threshold 50% → 정확히 5번째 실패에서 OPEN
    private static final int WARMUP_CALLS = 10;      // sliding-window-size와 동일
    private static final int FAILURES_TO_TRIP = 5;   // window(10) × threshold(50%)

    @Autowired
    private EventCommandRepository eventCommandRepository;

    @Autowired
    private RedisHotPathCircuitBreaker circuitBreaker;

    // 빈 이름(redisContainer)으로 매칭 — PostgreSQLContainer도 GenericContainer라 타입만으론 모호함
    @Autowired
    private GenericContainer<?> redisContainer;

    // 리셋용 델리게이트 — 래퍼는 reset을 노출하지 않으므로 resilience4j 빈을 직접 주입
    @Autowired
    private CircuitBreaker redisHotPathCircuitBreakerDelegate;

    @BeforeEach
    void resetBreakerWindow() {
        // 공유 컨텍스트의 다른 테스트가 남긴 호출 기록을 비워 전제(빈 창)를 통제한다
        redisHotPathCircuitBreakerDelegate.reset();
    }

    @Test
    void process_failsFastWithBreakerOpenWhenRedisIsDown() {
        UUID leaderboardId = UUID.randomUUID();

        // 웜업: 성공 호출로 sliding window를 채운다 (실패하면 테스트가 죽는 게 맞으므로 try/catch 없음)
        for (int i = 0; i < WARMUP_CALLS; i++) {
            EventPayload payload = newPayload(leaderboardId);
            eventCommandRepository.process(payload);
        }

        redisContainer.stop();

        // 실패 축적: 이 단계의 예외는 커넥션 계열이어야 한다 — 브레이커가 조기 개입하면 여기서 잡힌다
        for (int i = 0; i < FAILURES_TO_TRIP; i++) {
            EventPayload payload = newPayload(leaderboardId);
            try {
                eventCommandRepository.process(payload);
            } catch (Exception e) {
                assertNotEquals(RedisCircuitBreakerOpenException.class, e.getClass());
            }
        }

        // OPEN 후 10초면 자동 HALF_OPEN이므로 상태·fail-fast 검증은 열린 직후에 수행한다
        CircuitBreakerStatus cbs = circuitBreaker.getStatus();
        assertEquals("OPEN", cbs.state());

        assertThrows(RedisCircuitBreakerOpenException.class, () -> eventCommandRepository.process(newPayload(leaderboardId)));
    }

    private EventPayload newPayload(UUID leaderboardId) {
        return new EventPayload(leaderboardId, 1L, 10L, UUID.randomUUID());
    }
}

package com.jake.realtimeapi.infra.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 랭킹 읽기 전용 브레이커. 쓰기 브레이커와 창을 공유하지 않아서,
 * 쓰기만 실패하는 장애(예: noeviction OOM으로 Lua SET 거부)에서 읽기가 같이 막히지 않는다.
 */
@Component
public class RedisReadPathCircuitBreaker {

    private final CircuitBreaker circuitBreaker;

    public RedisReadPathCircuitBreaker(
            @Qualifier("redisReadPathCircuitBreakerDelegate") CircuitBreaker redisReadPathCircuitBreakerDelegate,
            MeterRegistry meterRegistry
    ) {
        this.circuitBreaker = redisReadPathCircuitBreakerDelegate;

        Gauge.builder("circuit_breaker_failure_rate", this, RedisReadPathCircuitBreaker::failureRate)
                .tag("name", circuitBreaker.getName())
                .register(meterRegistry);
        Gauge.builder("circuit_breaker_slow_call_rate", this, RedisReadPathCircuitBreaker::slowCallRate)
                .tag("name", circuitBreaker.getName())
                .register(meterRegistry);
        registerStateGauge(meterRegistry, "closed");
        registerStateGauge(meterRegistry, "half_open");
        registerStateGauge(meterRegistry, "open");
    }

    /**
     * Redis 읽기를 시도하고, 못 읽으면 fallback으로 답한다.
     * 자동 OPEN·개별 Redis 실패 모두 fallback이다. 느린 호출은 결과를 그대로 돌려주되 창에 slow로 기록된다.
     */
    public <T> T execute(Supplier<T> redisCall, Supplier<T> fallback) {
        try {
            return circuitBreaker.executeSupplier(redisCall);
        } catch (CallNotPermittedException | DataAccessException exception) {
            return fallback.get();
        }
    }

    public long retryAfterSeconds() {
        return circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1) / 1000;
    }

    public ReadPathCircuitBreakerStatus getStatus() {
        return new ReadPathCircuitBreakerStatus(currentState(), failureRate(), slowCallRate());
    }

    private void registerStateGauge(MeterRegistry meterRegistry, String state) {
        Gauge.builder("circuit_breaker_state", this, breaker -> breaker.currentState().equalsIgnoreCase(state) ? 1 : 0)
                .tag("name", circuitBreaker.getName())
                .tag("state", state)
                .register(meterRegistry);
    }

    private String currentState() {
        return circuitBreaker.getState().name();
    }

    private double failureRate() {
        float failureRate = circuitBreaker.getMetrics().getFailureRate();
        return failureRate < 0 ? 0.0 : failureRate;
    }

    private double slowCallRate() {
        float slowCallRate = circuitBreaker.getMetrics().getSlowCallRate();
        return slowCallRate < 0 ? 0.0 : slowCallRate;
    }
}

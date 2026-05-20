package com.jake.realtimeapi.infra.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Component
public class RedisHotPathCircuitBreaker {

    private final CircuitBreaker circuitBreaker;
    private final RedisHotPathCircuitBreakerProperties properties;
    private final AtomicReference<String> manualOpenReason = new AtomicReference<>();

    public RedisHotPathCircuitBreaker(
            CircuitBreaker redisHotPathCircuitBreakerDelegate,
            RedisHotPathCircuitBreakerProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.circuitBreaker = redisHotPathCircuitBreakerDelegate;
        this.properties = properties;

        Gauge.builder("circuit_breaker_failure_rate", this, RedisHotPathCircuitBreaker::failureRate)
                .register(meterRegistry);
        registerStateGauge(meterRegistry, "closed");
        registerStateGauge(meterRegistry, "half_open");
        registerStateGauge(meterRegistry, "open");
    }

    public <T> T execute(Supplier<T> supplier) {
        if (isManualOpen()) {
            throw new RedisCircuitBreakerOpenException(properties.waitDurationInOpenState().toSeconds());
        }
        try {
            return circuitBreaker.executeSupplier(supplier);
        } catch (CallNotPermittedException exception) {
            throw new RedisCircuitBreakerOpenException(properties.waitDurationInOpenState().toSeconds());
        }
    }

    public CircuitBreakerStatus getStatus() {
        return new CircuitBreakerStatus(currentState(), failureRate());
    }

    public void openManually(String reason) {
        // Cold start recovery처럼 운영 절차가 우선인 구간에서는 자동 상태와 무관하게 강제로 OPEN 할 수 있다.
        manualOpenReason.set(reason == null || reason.isBlank() ? "manual-open" : reason);
    }

    public void clearManualOverride() {
        manualOpenReason.set(null);
    }

    private void registerStateGauge(MeterRegistry meterRegistry, String state) {
        Gauge.builder("circuit_breaker_state", this, breaker -> breaker.currentState().equalsIgnoreCase(state) ? 1 : 0)
                .tag("state", state)
                .register(meterRegistry);
    }

    private boolean isManualOpen() {
        return manualOpenReason.get() != null;
    }

    private String currentState() {
        if (isManualOpen()) {
            return "OPEN";
        }
        return circuitBreaker.getState().name();
    }

    private double failureRate() {
        float failureRate = circuitBreaker.getMetrics().getFailureRate();
        return failureRate < 0 ? 0.0 : failureRate;
    }
}

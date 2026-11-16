package com.jake.realtimeapi.infra.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class RedisHotPathCircuitBreaker {

    private final CircuitBreaker circuitBreaker;
    private final RedisHotPathCircuitBreakerProperties properties;

    public RedisHotPathCircuitBreaker(
            @Qualifier("redisHotPathCircuitBreakerDelegate") CircuitBreaker redisHotPathCircuitBreakerDelegate,
            RedisHotPathCircuitBreakerProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.circuitBreaker = redisHotPathCircuitBreakerDelegate;
        this.properties = properties;

        Gauge.builder("circuit_breaker_failure_rate", this, RedisHotPathCircuitBreaker::failureRate)
                .tag("name", circuitBreaker.getName())
                .register(meterRegistry);
        registerStateGauge(meterRegistry, "closed");
        registerStateGauge(meterRegistry, "half_open");
        registerStateGauge(meterRegistry, "open");
    }

    public <T> T execute(Supplier<T> supplier) {
        try {
            return circuitBreaker.executeSupplier(supplier);
        } catch (CallNotPermittedException exception) {
            throw new RedisCircuitBreakerOpenException(properties.waitDurationInOpenState().toSeconds());
        }
    }

    public CircuitBreakerStatus getStatus() {
        return new CircuitBreakerStatus(currentState(), failureRate());
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
}

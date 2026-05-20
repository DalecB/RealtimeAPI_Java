package com.jake.realtimeapi.infra.circuitbreaker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "events.circuit-breaker")
public record RedisHotPathCircuitBreakerProperties(
        int slidingWindowSize,
        float failureRateThreshold,
        Duration waitDurationInOpenState,
        int permittedCallsInHalfOpenState
) {
}

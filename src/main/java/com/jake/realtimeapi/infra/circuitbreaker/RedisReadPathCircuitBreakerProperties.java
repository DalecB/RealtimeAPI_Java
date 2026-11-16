package com.jake.realtimeapi.infra.circuitbreaker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "rankings.circuit-breaker")
public record RedisReadPathCircuitBreakerProperties(
        int slidingWindowSize,
        float failureRateThreshold,
        Duration slowCallDurationThreshold,
        float slowCallRateThreshold,
        Duration waitDurationInOpenState,
        int permittedCallsInHalfOpenState
) {
}

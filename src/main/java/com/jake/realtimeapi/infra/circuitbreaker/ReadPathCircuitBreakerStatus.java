package com.jake.realtimeapi.infra.circuitbreaker;

public record ReadPathCircuitBreakerStatus(
        String state,
        double failureRate,
        double slowCallRate
) {
}

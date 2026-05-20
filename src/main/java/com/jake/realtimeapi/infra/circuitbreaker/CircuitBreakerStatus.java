package com.jake.realtimeapi.infra.circuitbreaker;

public record CircuitBreakerStatus(
        String state,
        double failureRate
) {
}

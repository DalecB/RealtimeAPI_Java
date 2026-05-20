package com.jake.realtimeapi.infra.api.dto;

public record CircuitBreakerStatusResponse(
        String state,
        double failureRate
) {
}

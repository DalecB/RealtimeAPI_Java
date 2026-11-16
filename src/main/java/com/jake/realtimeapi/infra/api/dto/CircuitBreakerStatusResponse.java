package com.jake.realtimeapi.infra.api.dto;

// 최상위 state/failureRate는 쓰기 브레이커다. 기존 콘솔이 읽는 계약이라 그대로 두고 읽기 브레이커는 read에 붙인다.
public record CircuitBreakerStatusResponse(
        String state,
        double failureRate,
        ReadPath read
) {
    public record ReadPath(
            String state,
            double failureRate,
            double slowCallRate
    ) {
    }
}

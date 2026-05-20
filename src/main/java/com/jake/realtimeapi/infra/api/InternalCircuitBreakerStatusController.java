package com.jake.realtimeapi.infra.api;

import com.jake.realtimeapi.infra.api.dto.CircuitBreakerStatusResponse;
import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/circuit-breaker")
public class InternalCircuitBreakerStatusController {

    private final RedisHotPathCircuitBreaker redisHotPathCircuitBreaker;

    public InternalCircuitBreakerStatusController(RedisHotPathCircuitBreaker redisHotPathCircuitBreaker) {
        this.redisHotPathCircuitBreaker = redisHotPathCircuitBreaker;
    }

    @GetMapping("/status")
    public CircuitBreakerStatusResponse getStatus() {
        var status = redisHotPathCircuitBreaker.getStatus();
        return new CircuitBreakerStatusResponse(status.state(), status.failureRate());
    }
}

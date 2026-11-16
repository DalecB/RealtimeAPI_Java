package com.jake.realtimeapi.infra.api;

import com.jake.realtimeapi.infra.api.dto.CircuitBreakerStatusResponse;
import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import com.jake.realtimeapi.infra.circuitbreaker.RedisReadPathCircuitBreaker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/circuit-breaker")
public class InternalCircuitBreakerStatusController {

    private final RedisHotPathCircuitBreaker redisHotPathCircuitBreaker;
    private final RedisReadPathCircuitBreaker redisReadPathCircuitBreaker;

    public InternalCircuitBreakerStatusController(
            RedisHotPathCircuitBreaker redisHotPathCircuitBreaker,
            RedisReadPathCircuitBreaker redisReadPathCircuitBreaker
    ) {
        this.redisHotPathCircuitBreaker = redisHotPathCircuitBreaker;
        this.redisReadPathCircuitBreaker = redisReadPathCircuitBreaker;
    }

    @GetMapping("/status")
    public CircuitBreakerStatusResponse getStatus() {
        var write = redisHotPathCircuitBreaker.getStatus();
        var read = redisReadPathCircuitBreaker.getStatus();
        return new CircuitBreakerStatusResponse(
                write.state(),
                write.failureRate(),
                new CircuitBreakerStatusResponse.ReadPath(read.state(), read.failureRate(), read.slowCallRate())
        );
    }
}

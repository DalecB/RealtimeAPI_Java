package com.jake.realtimeapi.infra.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;

@Configuration
@EnableConfigurationProperties(RedisReadPathCircuitBreakerProperties.class)
public class RedisReadPathCircuitBreakerConfig {

    @Bean
    public CircuitBreaker redisReadPathCircuitBreakerDelegate(RedisReadPathCircuitBreakerProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.slidingWindowSize())
                .failureRateThreshold(properties.failureRateThreshold())
                // 읽기는 실패보다 느려지는 게 먼저 온다. Redis timeout(2s)을 다 기다리기 전에 열리도록 지연도 센다.
                .slowCallDurationThreshold(properties.slowCallDurationThreshold())
                .slowCallRateThreshold(properties.slowCallRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Redis 접근 실패(연결·타임아웃·명령 오류)만 센다. LeaderboardNotFound 같은 업무 예외는 브레이커와 무관하다.
                .recordExceptions(DataAccessException.class)
                .build();

        return CircuitBreaker.of("redisReadPath", config);
    }
}

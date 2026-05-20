package com.jake.realtimeapi.infra.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RedisHotPathCircuitBreakerProperties.class)
public class RedisHotPathCircuitBreakerConfig {

    @Bean
    public CircuitBreaker redisHotPathCircuitBreakerDelegate(RedisHotPathCircuitBreakerProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.slidingWindowSize())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .ignoreExceptions(IllegalArgumentException.class)
                .build();

        return CircuitBreaker.of("redisHotPath", config);
    }
}

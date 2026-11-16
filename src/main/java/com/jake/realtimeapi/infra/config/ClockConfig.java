package com.jake.realtimeapi.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// 스냅샷 나이처럼 시간에 따라 판정이 갈리는 코드가 테스트에서 시계를 고정할 수 있게 주입한다.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

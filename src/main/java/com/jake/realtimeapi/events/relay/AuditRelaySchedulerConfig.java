package com.jake.realtimeapi.events.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "events.relay.enabled", havingValue = "true")
public class AuditRelaySchedulerConfig {

    public static final String SCHEDULER = "auditRelayTaskScheduler";

    // 일반 빈이면 Boot가 기본 taskScheduler를 만들지 않아 모든 @Scheduled가 이 스레드로 몰리므로 기본 후보에서 뺀다.
    @Bean(name = SCHEDULER, defaultCandidate = false)
    ThreadPoolTaskScheduler auditRelayTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("audit-relay-");
        return scheduler;
    }
}

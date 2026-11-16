package com.jake.realtimeapi.events.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 릴레이는 Kafka 장애 동안 스레드를 붙잡으므로 트림과 함께 전용 스레드에서 직렬로 돌고, 다른 작업은 Boot 기본 스케줄러에 남아야 한다.
class RelayAndTrimShareSchedulerThreadTest {

    private static final List<String> tickThreads = new CopyOnWriteArrayList<>();
    private static final CountDownLatch bothTicked = new CountDownLatch(2);
    private static final List<String> otherJobThreads = new CopyOnWriteArrayList<>();
    private static final CountDownLatch otherJobTicked = new CountDownLatch(1);

    @Test
    void relayAndTrimShareTheDedicatedThread_otherJobsStayOnTheDefaultScheduler() throws InterruptedException {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Config.class)
                .web(WebApplicationType.NONE)
                .run("--events.relay.enabled=true")) {
            assertTrue(bothTicked.await(5, TimeUnit.SECONDS));
            assertTrue(otherJobTicked.await(5, TimeUnit.SECONDS));
            assertInstanceOf(ThreadPoolTaskScheduler.class, context.getBean("taskScheduler"));
        }

        assertEquals(2, tickThreads.size());
        assertEquals(tickThreads.get(0), tickThreads.get(1), "relay and trim ticked on " + tickThreads);
        assertTrue(tickThreads.get(0).startsWith("audit-relay-"), tickThreads.get(0));
        assertTrue(otherJobThreads.get(0).startsWith("scheduling-"), otherJobThreads.get(0));
        assertNotEquals(tickThreads.get(0), otherJobThreads.get(0));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ImportAutoConfiguration(TaskSchedulingAutoConfiguration.class)
    @Import({AuditRelaySchedulerConfig.class, AuditRelayWorker.class, AuditStreamTrimWorker.class})
    static class Config {

        @Bean
        AuditRelayLeaderLock leaderLock() {
            AuditRelayLeaderLock lock = mock(AuditRelayLeaderLock.class);
            when(lock.isLeader()).thenAnswer(invocation -> {
                tickThreads.add(Thread.currentThread().getName());
                bothTicked.countDown();
                // 먼저 온 틱이 잠시 붙잡고 있어야 스레드가 둘 이상일 때 다른 틱이 다른 스레드에서 들어와 차이가 드러난다.
                bothTicked.await(500, TimeUnit.MILLISECONDS);
                return false;
            });
            return lock;
        }

        @Bean
        OtherJob otherJob() {
            return new OtherJob();
        }

        @Bean
        LeaderboardRepository leaderboardRepository() {
            return mock(LeaderboardRepository.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate() {
            return mock(KafkaTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    static class OtherJob {

        @Scheduled(fixedDelay = 60_000)
        public void tick() {
            otherJobThreads.add(Thread.currentThread().getName());
            otherJobTicked.countDown();
        }
    }
}

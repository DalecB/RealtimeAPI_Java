package com.jake.realtimeapi.infra.config;

import com.jake.realtimeapi.events.relay.AuditRelaySchedulerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// phaseZeroResource는 LettuceConnectionFactory(phase 0) 대역이고, 기본 스케줄러 종료 설정은 application.properties 값을 그대로 쓴다.
// 스케줄러마다 컨텍스트를 따로 띄운다. 한 컨텍스트에 두면 한쪽의 대기가 다른 쪽의 조기 종료를 가린다.
class SchedulerShutdownOrderTest {

    private static final List<String> events = new CopyOnWriteArrayList<>();
    private static volatile CountDownLatch tickStarted;

    @BeforeEach
    void reset() {
        events.clear();
        tickStarted = new CountDownLatch(1);
    }

    @Test
    void inFlightTickFinishesBeforePhaseZeroBeansStop() throws InterruptedException {
        closeDuringTick(DefaultSchedulerConfig.class);

        assertEquals(List.of("tick-end", "phase0-stop"), events);
    }

    @Test
    void inFlightRelayTickFinishesBeforePhaseZeroBeansStop() throws InterruptedException {
        closeDuringTick(RelaySchedulerConfig.class);

        assertEquals(List.of("relay-tick-end", "phase0-stop"), events);
    }

    private void closeDuringTick(Class<?> config) throws InterruptedException {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(config)
                .web(WebApplicationType.NONE)
                .run("--events.relay.enabled=true");
        assertTrue(tickStarted.await(5, TimeUnit.SECONDS));

        context.close();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PhaseZeroConfig.class)
    static class DefaultSchedulerConfig {

        @Bean
        SlowTick slowTick() {
            return new SlowTick();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({PhaseZeroConfig.class, AuditRelaySchedulerConfig.class})
    static class RelaySchedulerConfig {

        @Bean
        SlowRelayTick slowRelayTick() {
            return new SlowRelayTick();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ImportAutoConfiguration(TaskSchedulingAutoConfiguration.class)
    static class PhaseZeroConfig {

        @Bean
        SmartLifecycle phaseZeroResource() {
            return new SmartLifecycle() {
                private volatile boolean running;

                @Override
                public void start() {
                    running = true;
                }

                @Override
                public void stop() {
                    events.add("phase0-stop");
                    running = false;
                }

                @Override
                public boolean isRunning() {
                    return running;
                }

                @Override
                public int getPhase() {
                    return 0;
                }
            };
        }
    }

    static class SlowTick {

        @Scheduled(fixedDelay = 60_000)
        public void tick() throws InterruptedException {
            tickStarted.countDown();
            Thread.sleep(1_000);
            events.add("tick-end");
        }
    }

    static class SlowRelayTick {

        @Scheduled(fixedDelay = 60_000, scheduler = AuditRelaySchedulerConfig.SCHEDULER)
        public void tick() throws InterruptedException {
            tickStarted.countDown();
            Thread.sleep(1_000);
            events.add("relay-tick-end");
        }
    }
}

package com.jake.realtimeapi.infra.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * application.properties 설정 그대로 띄운 자동 구성 스케줄러가, 종료 시 실행 중인 틱을 끝낸 뒤에야
 * phase 0 빈(LettuceConnectionFactory와 같은 단계)을 멈추는지 확인한다.
 */
class SchedulerShutdownOrderTest {

    private static final List<String> events = new CopyOnWriteArrayList<>();
    private static final CountDownLatch tickStarted = new CountDownLatch(1);

    @Test
    void inFlightTickFinishesBeforePhaseZeroBeansStop() throws InterruptedException {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Config.class)
                .web(WebApplicationType.NONE)
                .run();
        assertTrue(tickStarted.await(5, TimeUnit.SECONDS));

        context.close();

        assertEquals(List.of("tick-end", "phase0-stop"), events);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ImportAutoConfiguration(TaskSchedulingAutoConfiguration.class)
    static class Config {

        @Bean
        SlowTick slowTick() {
            return new SlowTick();
        }

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
}

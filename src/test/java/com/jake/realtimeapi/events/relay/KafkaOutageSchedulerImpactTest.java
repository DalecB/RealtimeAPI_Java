package com.jake.realtimeapi.events.relay;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.consumer.AuditEventQueryRepository;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 측정용이다. 기본값(request 30s, delivery 120s, max.block 60s, relay batch 500)으로 돌리면 수십 분이 걸려 타임아웃을 줄였고, 줄인 비율은 출력에 같이 찍는다.
@SpringBootTest(properties = {
        "events.relay.enabled=true",
        "events.relay.delay-ms=500",
        "events.relay.batch-size=5",
        "events.relay.trim-delay-ms=3600000",
        "snapshots.worker.enabled=true",
        "snapshots.worker.delay-ms=500",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=" + KafkaOutageSchedulerImpactTest.DELIVERY_TIMEOUT_MS,
        "spring.kafka.producer.properties.max.block.ms=" + KafkaOutageSchedulerImpactTest.MAX_BLOCK_MS
})
@Import({TestcontainersConfiguration.class, KafkaOutageSchedulerImpactTest.RecorderConfig.class})
class KafkaOutageSchedulerImpactTest {

    static final long DELIVERY_TIMEOUT_MS = 2000;
    static final long MAX_BLOCK_MS = 1000;
    private static final int BATCH_SIZE = 5;
    private static final long API_KEY_ID = 7L;
    private static final String RELAY = "relayAll";
    private static final String USAGE_FLUSH = "flushPending";
    private static final String SNAPSHOT = "captureAllLeaderboards";

    @Autowired
    private ScheduledRunRecorder recorder;

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private ProducerFactory<String, String> producerFactory;

    @Autowired
    private EventCommandRepository eventCommandRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AuditEventQueryRepository auditEventQueryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private LeaderboardRepository leaderboardRepository;

    @Test
    void kafkaOutage_holdsTheSingleSchedulerThread_withoutLosingEvents() throws Exception {
        assertEquals(1, taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize());

        Fixture fixture = createFixture();
        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(fixture.leaderboardId());
        int written = 0;

        writeEvent(fixture);
        written++;
        await().atMost(Duration.ofSeconds(30)).until(() -> auditRowCount(fixture) == 1);

        boolean paused = false;
        try {
            pauseKafka();
            paused = true;

            awaitRelayTickEndedAfter(System.nanoTime());
            int backlog = 12;
            for (int i = 0; i < backlog; i++) {
                writeEvent(fixture);
            }
            written += backlog;
            long backlogWrittenAt = System.nanoTime();
            Run backlogTick = awaitRelayTickStartedAfter(backlogWrittenAt);
            long backlogBatches = (backlog + BATCH_SIZE - 1) / BATCH_SIZE;
            assertTrue(backlogTick.durationMs() >= (backlogBatches - 1) * DELIVERY_TIMEOUT_MS);
            assertEquals(backlog, pendingCount(streamKey));
            assertEquals(1, auditRowCount(fixture));
            report("A warm producer, fixed backlog", backlogTick,
                    backlogBatches + " batches x delivery.timeout " + DELIVERY_TIMEOUT_MS + "ms",
                    DELIVERY_TIMEOUT_MS, 120_000);

            long inflowStart = System.nanoTime();
            int inflow = 0;
            while (System.nanoTime() - inflowStart < Duration.ofSeconds(6).toNanos()) {
                writeEvent(fixture);
                inflow++;
                Thread.sleep(500);
            }
            written += inflow;
            long inflowEnd = System.nanoTime();
            Run inflowTick = awaitRelayTickEndedAfter(inflowEnd);
            assertTrue(inflowTick.startNanos() < inflowEnd - Duration.ofSeconds(4).toNanos());
            assertEquals(written - 1, pendingCount(streamKey));
            assertEquals(1, auditRowCount(fixture));
            System.out.printf("[C-15] B warm producer, 2 events/s inflow for 6s: relay tick %dms, started %dms after"
                            + " inflow start, ended %dms after inflow end; length follows the inflow, not the timeout%n",
                    inflowTick.durationMs(), ms(inflowTick.startNanos() - inflowStart),
                    ms(inflowTick.endNanos() - inflowEnd));

            producerFactory.reset();
            long resetAt = System.nanoTime();
            Run coldTick = awaitRelayTickStartedAfter(resetAt);
            assertTrue(coldTick.durationMs() >= MAX_BLOCK_MS * 9 / 10);
            assertTrue(coldTick.failed());
            assertEquals(written - 1, pendingCount(streamKey));
            report("C cold producer (no metadata)", coldTick,
                    "first send waits max.block " + MAX_BLOCK_MS + "ms, then KafkaException aborts the tick",
                    MAX_BLOCK_MS, 60_000);

            printOtherJobGaps(backlogWrittenAt, coldTick.endNanos());

            unpauseKafka();
            paused = false;
            long unpausedAt = System.nanoTime();
            int expected = written;
            await().atMost(Duration.ofSeconds(60)).until(() -> pendingCount(streamKey) == 0);
            long pelDrainedMs = ms(System.nanoTime() - unpausedAt);
            await().atMost(Duration.ofSeconds(60)).until(() -> auditRowCount(fixture) == expected);
            long rowsStoredMs = ms(System.nanoTime() - unpausedAt);
            assertEquals(expected, distinctAuditEventIds(fixture));
            System.out.printf("[C-15] recovery: events=%d PEL 0 after %dms, all %d rows (distinct %d) after %dms%n",
                    expected, pelDrainedMs, auditRowCount(fixture), distinctAuditEventIds(fixture), rowsStoredMs);

            Set<String> threads = recorder.runs.stream().map(Run::thread).collect(Collectors.toSet());
            assertEquals(1, threads.size());
            System.out.println("[C-15] scheduled job threads=" + threads);
        } finally {
            if (paused) {
                unpauseKafka();
            }
        }
    }

    private void report(String scenario, Run tick, String expectation, long testTimeoutMs, long defaultTimeoutMs) {
        long scale = defaultTimeoutMs / testTimeoutMs;
        System.out.printf("[C-15] %s: relay tick %dms (%s); same shape with defaults x%d -> ~%ds%n",
                scenario, tick.durationMs(), expectation, scale, tick.durationMs() * scale / 1000);
    }

    private void printOtherJobGaps(long fromNanos, long toNanos) {
        for (String job : List.of(USAGE_FLUSH, SNAPSHOT)) {
            List<Run> runs = recorder.runs.stream()
                    .filter(run -> run.method().equals(job))
                    .filter(run -> run.endNanos() >= fromNanos && run.startNanos() <= toNanos)
                    .toList();
            long maxGapMs = 0;
            for (int i = 1; i < runs.size(); i++) {
                maxGapMs = Math.max(maxGapMs, ms(runs.get(i).startNanos() - runs.get(i - 1).startNanos()));
            }
            System.out.printf("[C-15] %s during outage window %dms: runs=%d, max start-to-start gap %dms%n",
                    job, ms(toNanos - fromNanos), runs.size(), maxGapMs);
        }
    }

    private Run awaitRelayTickStartedAfter(long nanos) {
        return await().atMost(Duration.ofSeconds(60)).until(
                () -> recorder.runs.stream()
                        .filter(run -> run.method().equals(RELAY) && run.startNanos() > nanos)
                        .findFirst().orElse(null),
                run -> run != null);
    }

    private Run awaitRelayTickEndedAfter(long nanos) {
        return await().atMost(Duration.ofSeconds(60)).until(
                () -> recorder.runs.stream()
                        .filter(run -> run.method().equals(RELAY) && run.endNanos() > nanos)
                        .findFirst().orElse(null),
                run -> run != null);
    }

    private void pauseKafka() {
        kafkaContainer.getDockerClient().pauseContainerCmd(kafkaContainer.getContainerId()).exec();
    }

    private void unpauseKafka() {
        kafkaContainer.getDockerClient().unpauseContainerCmd(kafkaContainer.getContainerId()).exec();
    }

    private void writeEvent(Fixture fixture) {
        eventCommandRepository.process(
                new EventPayload(fixture.leaderboardId(), fixture.userId(), 1L, UUID.randomUUID()), API_KEY_ID);
    }

    private long pendingCount(String streamKey) {
        return redisTemplate.opsForStream().pending(streamKey, AuditRelayWorker.GROUP).getTotalPendingMessages();
    }

    private int auditRowCount(Fixture fixture) {
        return auditEventQueryRepository.recent(fixture.leaderboardId(), 1000).size();
    }

    private long distinctAuditEventIds(Fixture fixture) {
        return auditEventQueryRepository.recent(fixture.leaderboardId(), 1000).stream()
                .map(AuditEventQueryRepository.RecentAuditEvent::eventId)
                .distinct()
                .count();
    }

    private Fixture createFixture() {
        String token = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(User.newUser("outage-" + token));
        Project project = projectRepository.save(Project.newProject("outage-project-" + token, user.id()));
        Leaderboard leaderboard = leaderboardRepository.save(
                Leaderboard.newLeaderboard(project.id(), "outage-board-" + token));
        return new Fixture(user.id(), leaderboard.id());
    }

    private static long ms(long nanos) {
        return Duration.ofNanos(nanos).toMillis();
    }

    private record Fixture(long userId, UUID leaderboardId) {
    }

    record Run(String method, String thread, long startNanos, long endNanos, boolean failed) {
        long durationMs() {
            return ms(endNanos - startNanos);
        }
    }

    // 운영 코드를 건드리지 않고 @Scheduled 실행 구간을 재려고 Spring이 스케줄 작업마다 여는 Observation에 붙는다.
    static class ScheduledRunRecorder implements ObservationHandler<ScheduledTaskObservationContext> {

        final List<Run> runs = new CopyOnWriteArrayList<>();

        @Override
        public void onStart(ScheduledTaskObservationContext context) {
            context.put(Long.class, System.nanoTime());
        }

        @Override
        public void onStop(ScheduledTaskObservationContext context) {
            runs.add(new Run(context.getMethod().getName(), Thread.currentThread().getName(),
                    context.getRequired(Long.class), System.nanoTime(), context.getError() != null));
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ScheduledTaskObservationContext;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecorderConfig {

        @Bean
        ScheduledRunRecorder scheduledRunRecorder() {
            return new ScheduledRunRecorder();
        }
    }
}

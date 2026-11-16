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

// 기본값(request 30s, delivery 120s, max.block 60s, relay batch 500)으로 돌리면 수십 분이 걸려 타임아웃을 줄였고, 줄인 비율은 출력에 같이 찍는다.
@SpringBootTest(properties = {
        "events.relay.enabled=true",
        "events.relay.delay-ms=500",
        "events.relay.batch-size=5",
        "events.relay.trim-delay-ms=3600000",
        "snapshots.worker.enabled=true",
        "snapshots.worker.delay-ms=" + KafkaOutageSchedulerImpactTest.SNAPSHOT_PERIOD_MS,
        "app.usage-stats.flush-delay-ms=" + KafkaOutageSchedulerImpactTest.USAGE_FLUSH_PERIOD_MS,
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=" + KafkaOutageSchedulerImpactTest.DELIVERY_TIMEOUT_MS,
        "spring.kafka.producer.properties.max.block.ms=" + KafkaOutageSchedulerImpactTest.MAX_BLOCK_MS
})
@Import({TestcontainersConfiguration.class, KafkaOutageSchedulerImpactTest.RecorderConfig.class})
class KafkaOutageSchedulerImpactTest {

    static final long DELIVERY_TIMEOUT_MS = 2000;
    static final long MAX_BLOCK_MS = 1000;
    static final long SNAPSHOT_PERIOD_MS = 500;
    static final long USAGE_FLUSH_PERIOD_MS = 1000;
    // 공유 기본 스레드에서 다른 작업 한 번과 자기 실행 시간이 끼는 여유다. 분리 전에는 간격이 8.5s였다.
    private static final long CADENCE_TOLERANCE_MS = 1000;
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
    void kafkaOutage_holdsOnlyTheRelayThread_withoutLosingEvents() throws Exception {
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
            long outageStart = System.nanoTime();

            awaitRelayTickEndedAfter(outageStart);
            int backlog = 12;
            for (int i = 0; i < backlog; i++) {
                writeEvent(fixture);
            }
            written += backlog;
            long backlogWrittenAt = System.nanoTime();
            Run backlogTick = awaitRelayTickStartedAfter(backlogWrittenAt);
            assertTrue(backlogTick.durationMs() >= DELIVERY_TIMEOUT_MS * 9 / 10);
            assertTrue(backlogTick.durationMs() < 2 * DELIVERY_TIMEOUT_MS);
            assertEquals(BATCH_SIZE, pendingCount(streamKey));
            assertEquals(1, auditRowCount(fixture));
            report("A warm producer, fixed backlog", backlogTick,
                    "one batch x delivery.timeout " + DELIVERY_TIMEOUT_MS + "ms, then the drain stops",
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
            awaitRelayTickEndedAfter(inflowEnd);
            List<Run> inflowTicks = relayRunsBetween(inflowStart, inflowEnd);
            long longestInflowTickMs = inflowTicks.stream().mapToLong(Run::durationMs).max().orElseThrow();
            assertTrue(inflowTicks.size() >= 2);
            assertTrue(longestInflowTickMs < 2 * DELIVERY_TIMEOUT_MS);
            assertEquals(BATCH_SIZE, pendingCount(streamKey));
            assertEquals(1, auditRowCount(fixture));
            System.out.printf("[C-15] B warm producer, 2 events/s inflow for 6s: %d relay ticks, longest %dms;"
                            + " each tick retries the PEL batch only, so length no longer follows the inflow%n",
                    inflowTicks.size(), longestInflowTickMs);

            producerFactory.reset();
            long resetAt = System.nanoTime();
            Run coldTick = awaitRelayTickStartedAfter(resetAt);
            assertTrue(coldTick.durationMs() >= MAX_BLOCK_MS * 9 / 10);
            assertTrue(coldTick.durationMs() < 2 * MAX_BLOCK_MS);
            assertEquals(BATCH_SIZE, pendingCount(streamKey));
            report("C cold producer (no metadata)", coldTick,
                    "first send waits max.block " + MAX_BLOCK_MS + "ms, then the leaderboard is skipped",
                    MAX_BLOCK_MS, 60_000);

            long outageEnd = coldTick.endNanos();
            assertCadence(USAGE_FLUSH, USAGE_FLUSH_PERIOD_MS, outageStart, outageEnd);
            assertCadence(SNAPSHOT, SNAPSHOT_PERIOD_MS, outageStart, outageEnd);

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

            Set<String> relayThreads = threadsOf(RELAY);
            Set<String> otherThreads = threadsOf(USAGE_FLUSH, SNAPSHOT);
            assertEquals(Set.of("audit-relay-1"), relayThreads);
            assertTrue(otherThreads.stream().allMatch(thread -> thread.startsWith("scheduling-")), otherThreads.toString());
            System.out.println("[C-15] relay threads=" + relayThreads + ", other job threads=" + otherThreads);
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

    private void assertCadence(String job, long periodMs, long fromNanos, long toNanos) {
        List<Long> starts = recorder.runs.stream()
                .filter(run -> run.method().equals(job))
                .map(Run::startNanos)
                .filter(start -> start >= fromNanos && start <= toNanos)
                .sorted()
                .toList();
        long maxGapMs = 0;
        long previous = fromNanos;
        for (long start : starts) {
            maxGapMs = Math.max(maxGapMs, ms(start - previous));
            previous = start;
        }
        maxGapMs = Math.max(maxGapMs, ms(toNanos - previous));
        System.out.printf("[C-15] %s during outage window %dms: runs=%d, max gap %dms (period %dms)%n",
                job, ms(toNanos - fromNanos), starts.size(), maxGapMs, periodMs);
        assertTrue(maxGapMs <= periodMs + CADENCE_TOLERANCE_MS, job + " max gap " + maxGapMs + "ms");
    }

    private List<Run> relayRunsBetween(long fromNanos, long toNanos) {
        return recorder.runs.stream()
                .filter(run -> run.method().equals(RELAY))
                .filter(run -> run.endNanos() >= fromNanos && run.startNanos() <= toNanos)
                .toList();
    }

    private Set<String> threadsOf(String... methods) {
        Set<String> names = Set.of(methods);
        return recorder.runs.stream()
                .filter(run -> names.contains(run.method()))
                .map(Run::thread)
                .collect(Collectors.toSet());
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

    record Run(String method, String thread, long startNanos, long endNanos) {
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
                    context.getRequired(Long.class), System.nanoTime()));
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

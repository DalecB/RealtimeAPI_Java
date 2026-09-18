package com.jake.realtimeapi.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.events.consumer.AuditEventQueryRepository;
import com.jake.realtimeapi.events.consumer.AuditConsumerStatus;
import com.jake.realtimeapi.events.consumer.AuditEventRepository;
import com.jake.realtimeapi.events.relay.AuditRelayWorker;
import com.jake.realtimeapi.events.relay.AuditTopicStatusReader;
import com.jake.realtimeapi.events.domain.repository.AuditStreamStatusRepository;
import com.jake.realtimeapi.infra.config.AuditConsumerErrorConfig;
import com.jake.realtimeapi.infra.config.AuditTopicConfig;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.backoff.ExponentialBackOff;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/** 실제 Redis·Kafka·PostgreSQL을 사용하는 audit event 왕복 통합 테스트. */
@SpringBootTest(properties = {
        "events.relay.enabled=true",
        "events.relay.delay-ms=3600000",
        "events.relay.trim-delay-ms=3600000",
        "events.relay.claim-min-idle-ms=0",
        "events.consumer.parse-retry-delay-ms=10",
        "events.consumer.db-retry-initial-delay=100ms",
        "events.consumer.db-retry-max-delay=200ms",
        "events.consumer.db-retry-unhealthy-after=200ms",
        "spring.datasource.hikari.connection-timeout=250",
        "spring.datasource.hikari.data-source-properties.socketTimeout=1"
})
@Import(TestcontainersConfiguration.class)
class KafkaAuditRoundTripTest {

    private static final long DELTA = 25L;
    private static final long API_KEY_ID = 7L;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private LeaderboardRepository leaderboardRepository;

    @Autowired
    private EventCommandRepository eventCommandRepository;

    @Autowired
    private AuditRelayWorker auditRelayWorker;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditEventQueryRepository auditEventQueryRepository;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private AdminClient adminClient;

    @Autowired
    private AuditTopicStatusReader auditTopicStatusReader;

    @Autowired
    private AuditStreamStatusRepository auditStreamStatusRepository;

    @Autowired
    private AuditConsumerStatus auditConsumerStatus;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private AuditEventRepository auditEventRepository;

    @Autowired
    private PostgreSQLContainer<?> postgresContainer;

    @Test
    void processedEvent_isRelayedThroughKafka_andStoredInAuditEvents() {
        Fixture fixture = createFixture();
        UUID idempotencyKey = UUID.randomUUID();
        EventPayload payload = new EventPayload(fixture.leaderboardId(), fixture.userId(), DELTA, idempotencyKey);

        eventCommandRepository.process(payload, API_KEY_ID);

        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(fixture.leaderboardId());
        List<MapRecord<String, Object, Object>> streamRecords = redisTemplate.opsForStream()
                .range(streamKey, Range.unbounded());
        assertEquals(1, streamRecords.size());
        String eventId = streamRecords.get(0).getId().getValue();
        assertEquals(1L, auditStreamStatusRepository.getStatus(fixture.leaderboardId()).consumerGroupLag());

        auditRelayWorker.relayAll();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditEventQueryRepository.RecentAuditEvent> rows = findAuditRows(fixture.leaderboardId());
            assertEquals(1, rows.size());

            AuditEventQueryRepository.RecentAuditEvent row = rows.get(0);
            assertEquals(fixture.leaderboardId(), row.leaderboardId());
            assertEquals(eventId, row.eventId());
            assertEquals("new", row.eventType());
            assertEquals(fixture.userId(), row.userId());
            assertEquals(DELTA, row.delta());
            assertEquals(API_KEY_ID, row.apiKeyId());
            assertEquals(idempotencyKey.toString(), row.idempotencyKey());
        });

        PendingMessagesSummary pending = redisTemplate.opsForStream().pending(streamKey, AuditRelayWorker.GROUP);
        assertNotNull(pending);
        assertEquals(0, pending.getTotalPendingMessages());
        assertEquals(0L, auditStreamStatusRepository.getStatus(fixture.leaderboardId()).consumerGroupLag());
    }

    @Test
    void stalePendingMessage_isClaimedByReplacementRelay_andStoredOnce() {
        Fixture fixture = createFixture();
        EventPayload payload = new EventPayload(
                fixture.leaderboardId(),
                fixture.userId(),
                DELTA,
                UUID.randomUUID()
        );
        eventCommandRepository.process(payload, API_KEY_ID);

        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(fixture.leaderboardId());
        readAsStoppedRelay(streamKey, 1);
        assertEquals(1, pendingCount(streamKey));

        auditRelayWorker.relayAll();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(1, findAuditRows(fixture.leaderboardId()).size());
            assertEquals(0, pendingCount(streamKey));
        });
    }

    @Test
    void pendingMessage_isClaimedOnlyAfterMinIdle() {
        Fixture fixture = createFixture();
        eventCommandRepository.process(new EventPayload(
                fixture.leaderboardId(),
                fixture.userId(),
                DELTA,
                UUID.randomUUID()
        ), API_KEY_ID);

        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(fixture.leaderboardId());
        readAsStoppedRelay(streamKey, 1);

        AuditRelayWorker delayedClaimRelay = relay("replacement-relay", 1, 1_000);

        delayedClaimRelay.relayAll();

        assertEquals(0, findAuditRows(fixture.leaderboardId()).size());
        assertEquals(1, pendingCount(streamKey));

        await().pollDelay(Duration.ofMillis(1_100)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            delayedClaimRelay.relayAll();
            assertEquals(1, findAuditRows(fixture.leaderboardId()).size());
            assertEquals(0, pendingCount(streamKey));
        });
    }

    @Test
    void stalePendingMessages_areClaimedAcrossRecoveryBatches() {
        Fixture fixture = createFixture();
        for (int i = 0; i < 3; i++) {
            eventCommandRepository.process(new EventPayload(
                    fixture.leaderboardId(),
                    fixture.userId(),
                    DELTA,
                    UUID.randomUUID()
            ), API_KEY_ID);
        }

        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(fixture.leaderboardId());
        readAsStoppedRelay(streamKey, 3);
        assertEquals(3, pendingCount(streamKey));

        AuditRelayWorker twoAtATimeRelay = relay("replacement-relay", 2, 0);

        twoAtATimeRelay.relayAll();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(2, findAuditRows(fixture.leaderboardId()).size());
            assertEquals(1, pendingCount(streamKey));
        });

        twoAtATimeRelay.relayAll();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(3, findAuditRows(fixture.leaderboardId()).size());
            assertEquals(0, pendingCount(streamKey));
        });
    }

    @Test
    void kafkaPublishedBeforeCrash_isDeduplicatedWhenPendingMessageIsRelayedAgain() throws Exception {
        Fixture fixture = createFixture();
        UUID idempotencyKey = UUID.randomUUID();
        eventCommandRepository.process(new EventPayload(
                fixture.leaderboardId(),
                fixture.userId(),
                DELTA,
                idempotencyKey
        ), API_KEY_ID);

        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(fixture.leaderboardId());
        List<MapRecord<String, Object, Object>> readByStoppedRelay = readAsStoppedRelay(streamKey, 1);
        assertEquals(1, pendingCount(streamKey));

        String eventId = readByStoppedRelay.get(0).getId().getValue();
        String key = fixture.leaderboardId().toString();
        kafkaTemplate.send(
                AuditTopicConfig.AUDIT_TOPIC,
                key,
                eventJson(fixture, eventId, idempotencyKey)
        ).get();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(1, findAuditRows(fixture.leaderboardId()).size()));

        auditRelayWorker.relayAll();

        String sentinelEventId = Instant.now().toEpochMilli() + "-0";
        kafkaTemplate.send(
                AuditTopicConfig.AUDIT_TOPIC,
                key,
                eventJson(fixture, sentinelEventId, UUID.randomUUID())
        ).get();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditEventQueryRepository.RecentAuditEvent> rows = findAuditRows(fixture.leaderboardId());
            assertEquals(2, rows.size());
            assertEquals(1, rows.stream().filter(row -> row.eventId().equals(eventId)).count());
            assertEquals(1, rows.stream().filter(row -> row.eventId().equals(sentinelEventId)).count());
            assertEquals(0, pendingCount(streamKey));
        });
    }

    @Test
    void duplicateKafkaDelivery_isStoredOnce() throws Exception {
        Fixture fixture = createFixture();
        long now = Instant.now().toEpochMilli();
        String eventId = now + "-0";
        String sentinelEventId = now + "-1";
        UUID idempotencyKey = UUID.randomUUID();

        String duplicateJson = eventJson(fixture, eventId, idempotencyKey);
        String sentinelJson = eventJson(fixture, sentinelEventId, UUID.randomUUID());
        String key = fixture.leaderboardId().toString();

        kafkaTemplate.send(AuditTopicConfig.AUDIT_TOPIC, key, duplicateJson).get();
        kafkaTemplate.send(AuditTopicConfig.AUDIT_TOPIC, key, duplicateJson).get();
        kafkaTemplate.send(AuditTopicConfig.AUDIT_TOPIC, key, sentinelJson).get();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditEventQueryRepository.RecentAuditEvent> rows = findAuditRows(fixture.leaderboardId());
            assertEquals(2, rows.size());
            assertEquals(1, rows.stream().filter(row -> row.eventId().equals(eventId)).count());
            assertEquals(1, rows.stream().filter(row -> row.eventId().equals(sentinelEventId)).count());
        });
    }

    @Test
    void malformedRecord_isSentToDlt_withoutBlockingValidRecord() throws Exception {
        Fixture fixture = createFixture();
        String key = fixture.leaderboardId().toString();
        String eventId = Instant.now().toEpochMilli() + "-0";
        MessageListenerContainer listener = kafkaListenerEndpointRegistry
                .getListenerContainer("audit-trend-consumer");
        assertNotNull(listener);
        listener.stop();

        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(!listener.isRunning()));

            SendResult<String, String> malformed = kafkaTemplate.send(
                    AuditTopicConfig.AUDIT_TOPIC, key, "not-json"
            ).get();
            SendResult<String, String> valid = kafkaTemplate.send(
                    AuditTopicConfig.AUDIT_TOPIC,
                    key,
                    eventJson(fixture, eventId, UUID.randomUUID())
            ).get();
            listener.start();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertEquals(1, findAuditRows(fixture.leaderboardId()).size());
                assertTrue(committedOffset(new TopicPartition(
                        AuditTopicConfig.AUDIT_TOPIC,
                        valid.getRecordMetadata().partition()
                )) >= valid.getRecordMetadata().offset() + 1);
            });

            ConsumerRecord<String, String> dlt = receive(
                    AuditTopicConfig.AUDIT_DLT_TOPIC,
                    malformed.getRecordMetadata().partition(),
                    0
            );
            assertNotNull(dlt);
            assertEquals(key, dlt.key());
            assertEquals("not-json", dlt.value());
            assertEquals(
                    AuditTopicConfig.AUDIT_TOPIC,
                    new String(
                            dlt.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                            StandardCharsets.UTF_8
                    )
            );
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                AuditTopicStatusReader.AuditTopicStatus status = auditTopicStatusReader.read();
                assertTrue(status.dltTotalMessages() >= 1L);
                assertTrue(status.dltRetained() >= 1L);
            });
        } finally {
            if (!listener.isRunning()) {
                listener.start();
            }
        }
    }

    @Test
    void listenerRestart_resumesFromCommittedOffset() throws Exception {
        Fixture fixture = createFixture();
        String key = fixture.leaderboardId().toString();
        long now = Instant.now().toEpochMilli();
        String firstEventId = now + "-0";
        String secondEventId = now + "-1";

        SendResult<String, String> first = kafkaTemplate.send(
                AuditTopicConfig.AUDIT_TOPIC,
                key,
                eventJson(fixture, firstEventId, UUID.randomUUID())
        ).get();
        TopicPartition partition = new TopicPartition(
                AuditTopicConfig.AUDIT_TOPIC,
                first.getRecordMetadata().partition()
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(1, findAuditRows(fixture.leaderboardId()).size());
            assertTrue(committedOffset(partition) >= first.getRecordMetadata().offset() + 1);
        });

        MessageListenerContainer listener = kafkaListenerEndpointRegistry
                .getListenerContainer("audit-trend-consumer");
        assertNotNull(listener);
        listener.stop();
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(!listener.isRunning()));

            SendResult<String, String> second = kafkaTemplate.send(
                    AuditTopicConfig.AUDIT_TOPIC,
                    key,
                    eventJson(fixture, secondEventId, UUID.randomUUID())
            ).get();
            assertEquals(partition.partition(), second.getRecordMetadata().partition());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertTrue(auditTopicStatusReader.read().consumerLag() >= 1L));

            listener.start();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                List<AuditEventQueryRepository.RecentAuditEvent> rows = findAuditRows(fixture.leaderboardId());
                assertEquals(2, rows.size());
                assertEquals(1, rows.stream().filter(row -> row.eventId().equals(firstEventId)).count());
                assertEquals(1, rows.stream().filter(row -> row.eventId().equals(secondEventId)).count());
                assertTrue(committedOffset(partition) >= second.getRecordMetadata().offset() + 1);
                assertEquals(0L, auditTopicStatusReader.read().consumerLag());
            });
        } finally {
            if (!listener.isRunning()) {
                listener.start();
            }
        }
    }

    @Test
    void postgresOutage_keepsAssignmentAndOffset_thenStoresSameBatchAfterRecovery() throws Exception {
        Fixture fixture = createFixture();
        String key = fixture.leaderboardId().toString();
        String eventId = Instant.now().toEpochMilli() + "-0";
        boolean postgresPaused = false;
        MessageListenerContainer listener = kafkaListenerEndpointRegistry
                .getListenerContainer("audit-trend-consumer");
        assertNotNull(listener);
        listener.stop();

        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(!listener.isRunning()));
            SendResult<String, String> sent = kafkaTemplate.send(
                    AuditTopicConfig.AUDIT_TOPIC,
                    key,
                    eventJson(fixture, eventId, UUID.randomUUID())
            ).get();
            TopicPartition partition = new TopicPartition(
                    AuditTopicConfig.AUDIT_TOPIC,
                    sent.getRecordMetadata().partition()
            );

            postgresContainer.getDockerClient()
                    .pauseContainerCmd(postgresContainer.getContainerId())
                    .exec();
            postgresPaused = true;
            listener.start();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertTrue(listener.getAssignedPartitions().contains(partition));
                assertTrue(committedOffset(partition) < sent.getRecordMetadata().offset() + 1);
                assertEquals("DOWN", auditConsumerStatus.health().getStatus().getCode());
            });

            postgresContainer.getDockerClient()
                    .unpauseContainerCmd(postgresContainer.getContainerId())
                    .exec();
            postgresPaused = false;

            await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertEquals(1, findAuditRows(fixture.leaderboardId()).size());
                assertTrue(committedOffset(partition) >= sent.getRecordMetadata().offset() + 1);
                assertEquals("UP", auditConsumerStatus.health().getStatus().getCode());
            });
        } finally {
            if (postgresPaused) {
                postgresContainer.getDockerClient()
                        .unpauseContainerCmd(postgresContainer.getContainerId())
                        .exec();
            }
            if (!listener.isRunning()) {
                listener.start();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoConsumers_spreadActualDatabaseRetriesWithJitter() throws Exception {
        ConcurrentMessageListenerContainer<String, String> listener =
                (ConcurrentMessageListenerContainer<String, String>) kafkaListenerEndpointRegistry
                        .getListenerContainer("audit-trend-consumer");
        assertNotNull(listener);
        listener.stop();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(!listener.isRunning()));

        int originalConcurrency = listener.getConcurrency();
        CommonErrorHandler originalErrorHandler = listener.getCommonErrorHandler();
        Map<UUID, List<DatabaseAttempt>> attempts = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            List<AuditEventRepository.AuditEventRow> rows = invocation.getArgument(0);
            long startedAt = System.nanoTime();
            String thread = Thread.currentThread().getName();
            for (AuditEventRepository.AuditEventRow row : rows) {
                attempts.computeIfAbsent(row.leaderboardId(), ignored -> new CopyOnWriteArrayList<>())
                        .add(new DatabaseAttempt(thread, startedAt));
            }
            return invocation.callRealMethod();
        }).when(auditEventRepository).insertIgnoringDuplicates(anyList());

        try {
            listener.setConcurrency(2);

            ExponentialBackOff deterministic = new ExponentialBackOff(400, 2);
            deterministic.setMaxInterval(800);
            RetryMeasurement fixed = measureDatabaseRetryTiming(
                    listener,
                    new DefaultErrorHandler(deterministic),
                    attempts
            );
            RetryMeasurement jittered = measureDatabaseRetryTiming(
                    listener,
                    new AuditConsumerErrorConfig().auditConsumerErrorHandler(
                            auditConsumerStatus,
                            Duration.ofMillis(400),
                            Duration.ofMillis(800)
                    ),
                    attempts
            );

            assertEquals(2, fixed.consumerThreads());
            assertEquals(2, jittered.consumerThreads());
            assertTrue(fixed.maxRetrySkewMs() < 150, fixed.toString());
            assertTrue(jittered.maxRetrySkewMs() > 50, jittered.toString());
            assertTrue(jittered.maxRetrySkewMs() > fixed.maxRetrySkewMs(),
                    "fixed=" + fixed + ", jittered=" + jittered);
            System.out.println("DB retry timing fixed=" + fixed + ", jittered=" + jittered);
        } finally {
            if (listener.isRunning()) {
                listener.stop();
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(!listener.isRunning()));
            }
            listener.setConcurrency(originalConcurrency);
            listener.setCommonErrorHandler(originalErrorHandler);
            listener.start();
        }
    }

    @Test
    void backlogLargerThanTwoPollBatches_isFullyDrained() throws Exception {
        Fixture fixture = createFixture();
        String key = fixture.leaderboardId().toString();
        long before = auditEventQueryRepository.count();
        long now = Instant.now().toEpochMilli();
        MessageListenerContainer listener = kafkaListenerEndpointRegistry
                .getListenerContainer("audit-trend-consumer");
        assertNotNull(listener);
        listener.stop();

        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(!listener.isRunning()));

            for (int i = 0; i < 1_001; i++) {
                kafkaTemplate.send(
                        AuditTopicConfig.AUDIT_TOPIC,
                        key,
                        eventJson(fixture, now + "-" + i, UUID.randomUUID())
                );
            }
            kafkaTemplate.flush();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertTrue(auditTopicStatusReader.read().consumerLag() >= 1_001L));

            listener.start();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertEquals(before + 1_001L, auditEventQueryRepository.count());
                assertEquals(0L, auditTopicStatusReader.read().consumerLag());
            });
        } finally {
            if (!listener.isRunning()) {
                listener.start();
            }
        }
    }

    @Test
    void stalePendingMessages_over500_areClaimedAcrossRecoveryCycles() {
        Fixture fixture = createFixture();
        for (int i = 0; i < 1_001; i++) {
            eventCommandRepository.process(new EventPayload(
                    fixture.leaderboardId(),
                    fixture.userId(),
                    DELTA,
                    UUID.randomUUID()
            ), API_KEY_ID);
        }

        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(fixture.leaderboardId());
        readAsStoppedRelay(streamKey, 1_001, "relay-a");
        AuditRelayWorker replacement = relay("relay-b", 500, 0);

        long started = System.nanoTime();
        replacement.relayAll();
        long firstBatchMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.printf("500-record relay batch: %d ms%n", firstBatchMillis);
        assertEquals(501, pendingCount(streamKey));

        replacement.relayAll();
        assertEquals(1, pendingCount(streamKey));

        replacement.relayAll();
        assertEquals(0, pendingCount(streamKey));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertEquals(1_001, auditEventQueryRepository.recent(fixture.leaderboardId(), 1_001).size()));
    }

    @Test
    void replacementRelay_isClaimedAgainAfterItStopsBeforeAck() {
        Fixture fixture = createFixture();
        eventCommandRepository.process(new EventPayload(
                fixture.leaderboardId(),
                fixture.userId(),
                DELTA,
                UUID.randomUUID()
        ), API_KEY_ID);

        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(fixture.leaderboardId());
        List<MapRecord<String, Object, Object>> claimedByA = readAsStoppedRelay(streamKey, 1, "relay-a");
        List<MapRecord<String, Object, Object>> claimedByB = redisTemplate.opsForStream().claim(
                streamKey,
                AuditRelayWorker.GROUP,
                "relay-b",
                Duration.ZERO,
                RecordId.of(claimedByA.get(0).getId().getValue())
        );
        assertEquals(1, claimedByB.size());

        relay("relay-c", 500, 0).relayAll();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(1, findAuditRows(fixture.leaderboardId()).size());
            assertEquals(0, pendingCount(streamKey));
        });
    }

    private String eventJson(Fixture fixture, String eventId, UUID idempotencyKey) throws Exception {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("type", "new");
        event.put("userId", Long.toString(fixture.userId()));
        event.put("delta", Long.toString(DELTA));
        event.put("apiKeyId", Long.toString(API_KEY_ID));
        event.put("idempotencyKey", idempotencyKey.toString());
        return objectMapper.writeValueAsString(event);
    }

    private Fixture createFixture() {
        String token = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(User.newUser("kafka-" + token));
        Project project = projectRepository.save(Project.newProject("kafka-project-" + token, user.id()));
        Leaderboard leaderboard = leaderboardRepository.save(
                Leaderboard.newLeaderboard(project.id(), "kafka-board-" + token));
        return new Fixture(user.id(), leaderboard.id());
    }

    private List<AuditEventQueryRepository.RecentAuditEvent> findAuditRows(UUID leaderboardId) {
        return auditEventQueryRepository.recent(leaderboardId, 100);
    }

    private RetryMeasurement measureDatabaseRetryTiming(
            ConcurrentMessageListenerContainer<String, String> listener,
            CommonErrorHandler errorHandler,
            Map<UUID, List<DatabaseAttempt>> attempts
    ) throws Exception {
        attempts.clear();
        Fixture first = createFixture();
        Fixture second = createFixture();
        long now = Instant.now().toEpochMilli();
        listener.setCommonErrorHandler(errorHandler);
        kafkaTemplate.send(
                AuditTopicConfig.AUDIT_TOPIC,
                0,
                first.leaderboardId().toString(),
                eventJson(first, now + "-0", UUID.randomUUID())
        ).get();
        kafkaTemplate.send(
                AuditTopicConfig.AUDIT_TOPIC,
                2,
                second.leaderboardId().toString(),
                eventJson(second, now + "-1", UUID.randomUUID())
        ).get();

        setAuditDatabaseOutage(true);
        listener.start();

        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals(2, listener.getAssignmentsByClientId().values().stream()
                            .filter(partitions -> !partitions.isEmpty())
                            .count()));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertTrue(attempts.getOrDefault(first.leaderboardId(), List.of()).size() >= 6);
                assertTrue(attempts.getOrDefault(second.leaderboardId(), List.of()).size() >= 6);
            });

            RetryMeasurement measurement = RetryMeasurement.from(
                    attempts.get(first.leaderboardId()),
                    attempts.get(second.leaderboardId())
            );
            setAuditDatabaseOutage(false);
            await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertEquals(1, findAuditRows(first.leaderboardId()).size());
                assertEquals(1, findAuditRows(second.leaderboardId()).size());
            });
            return measurement;
        } finally {
            setAuditDatabaseOutage(false);
            if (listener.isRunning()) {
                listener.stop();
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(!listener.isRunning()));
            }
        }
    }

    private void setAuditDatabaseOutage(boolean enabled) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS audit_events_outage ON audit_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_audit_insert()");
        if (enabled) {
            jdbcTemplate.execute("""
                    CREATE FUNCTION fail_audit_insert() RETURNS trigger AS $$
                    BEGIN
                        RAISE EXCEPTION 'simulated database outage' USING ERRCODE = '08006';
                        RETURN NULL;
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            jdbcTemplate.execute("""
                    CREATE TRIGGER audit_events_outage
                    BEFORE INSERT ON audit_events
                    FOR EACH STATEMENT EXECUTE FUNCTION fail_audit_insert()
                    """);
        }
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        OffsetAndMetadata committed = adminClient.listConsumerGroupOffsets("audit-trend")
                .partitionsToOffsetAndMetadata()
                .get()
                .get(partition);
        return committed == null ? -1L : committed.offset();
    }

    private ConsumerRecord<String, String> receive(String topic, int partition, long offset) {
        try (org.apache.kafka.clients.consumer.Consumer<String, String> consumer =
                     consumerFactory.createConsumer("dlt-test-" + UUID.randomUUID())) {
            TopicPartition topicPartition = new TopicPartition(topic, partition);
            consumer.assign(List.of(topicPartition));
            consumer.seek(topicPartition, offset);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(500)).records(topicPartition);
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
            throw new AssertionError("record not found: " + topicPartition + " offset=" + offset);
        }
    }

    private long pendingCount(String streamKey) {
        PendingMessagesSummary pending = redisTemplate.opsForStream().pending(streamKey, AuditRelayWorker.GROUP);
        assertNotNull(pending);
        return pending.getTotalPendingMessages();
    }

    private List<MapRecord<String, Object, Object>> readAsStoppedRelay(String streamKey, int count) {
        return readAsStoppedRelay(streamKey, count, "stopped-relay-" + UUID.randomUUID());
    }

    private List<MapRecord<String, Object, Object>> readAsStoppedRelay(
            String streamKey,
            int count,
            String consumerName
    ) {
        redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), AuditRelayWorker.GROUP);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(AuditRelayWorker.GROUP, consumerName),
                StreamReadOptions.empty().count(count),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );
        assertNotNull(records);
        assertEquals(count, records.size());
        return records;
    }

    private AuditRelayWorker relay(String consumerName, int batchSize, long claimMinIdleMs) {
        return new AuditRelayWorker(
                leaderboardRepository,
                redisTemplate,
                kafkaTemplate,
                objectMapper,
                batchSize,
                claimMinIdleMs,
                consumerName
        );
    }

    private record Fixture(long userId, UUID leaderboardId) {
    }

    private record DatabaseAttempt(String thread, long startedAtNanos) {
    }

    private record RetryMeasurement(int consumerThreads, List<Long> retrySkewsMs, long maxRetrySkewMs) {

        private static RetryMeasurement from(List<DatabaseAttempt> first, List<DatabaseAttempt> second) {
            int size = Math.min(first.size(), second.size());
            List<Long> skews = new ArrayList<>(size - 1);
            for (int i = 1; i < size; i++) {
                long firstOffset = first.get(i).startedAtNanos() - first.get(0).startedAtNanos();
                long secondOffset = second.get(i).startedAtNanos() - second.get(0).startedAtNanos();
                skews.add(Duration.ofNanos(Math.abs(firstOffset - secondOffset)).toMillis());
            }
            int threads = (int) java.util.stream.Stream.concat(first.stream(), second.stream())
                    .map(DatabaseAttempt::thread)
                    .distinct()
                    .count();
            long maxSkew = skews.stream().mapToLong(Long::longValue).max().orElse(0);
            return new RetryMeasurement(threads, List.copyOf(skews), maxSkew);
        }
    }

}

package com.jake.realtimeapi.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.events.consumer.AuditEventQueryRepository;
import com.jake.realtimeapi.events.relay.AuditRelayWorker;
import com.jake.realtimeapi.events.relay.AuditTopicStatusReader;
import com.jake.realtimeapi.events.domain.repository.AuditStreamStatusRepository;
import com.jake.realtimeapi.infra.config.AuditTopicConfig;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;

import org.apache.kafka.clients.admin.AdminClient;
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
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 Redis·Kafka·PostgreSQL을 사용하는 audit event 왕복 통합 테스트. */
@SpringBootTest(properties = {
        "events.relay.enabled=true",
        "events.relay.delay-ms=3600000",
        "events.relay.trim-delay-ms=3600000",
        "events.relay.claim-min-idle-ms=0"
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
        redisTemplate.opsForStream().createGroup(
                streamKey,
                ReadOffset.from("0"),
                AuditRelayWorker.GROUP
        );

        List<MapRecord<String, Object, Object>> readByStoppedRelay = redisTemplate.opsForStream().read(
                Consumer.from(AuditRelayWorker.GROUP, "stopped-relay-" + UUID.randomUUID()),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );

        assertNotNull(readByStoppedRelay);
        assertEquals(1, readByStoppedRelay.size());
        assertEquals(1, pendingCount(streamKey));

        auditRelayWorker.relayAll();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(1, findAuditRows(fixture.leaderboardId()).size());
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

    private long committedOffset(TopicPartition partition) throws Exception {
        OffsetAndMetadata committed = adminClient.listConsumerGroupOffsets("audit-trend")
                .partitionsToOffsetAndMetadata()
                .get()
                .get(partition);
        return committed == null ? -1L : committed.offset();
    }

    private long pendingCount(String streamKey) {
        PendingMessagesSummary pending = redisTemplate.opsForStream().pending(streamKey, AuditRelayWorker.GROUP);
        assertNotNull(pending);
        return pending.getTotalPendingMessages();
    }

    private record Fixture(long userId, UUID leaderboardId) {
    }

}

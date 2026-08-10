package com.jake.realtimeapi.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.events.consumer.AuditEventQueryRepository;
import com.jake.realtimeapi.events.relay.AuditRelayWorker;
import com.jake.realtimeapi.infra.config.AuditTopicConfig;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 실제 Redis·Kafka·PostgreSQL을 사용하는 audit event 왕복 통합 테스트. */
@SpringBootTest(properties = {
        "events.relay.enabled=true",
        "events.relay.delay-ms=3600000",
        "events.relay.trim-delay-ms=3600000"
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

    private record Fixture(long userId, UUID leaderboardId) {
    }

}

package com.jake.realtimeapi.events.relay;

import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuditStreamTrimWorkerTest {

    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private final LeaderboardRepository leaderboardRepository = mock(LeaderboardRepository.class);
    private final AuditRelayLeaderLock leaderLock = mock(AuditRelayLeaderLock.class);
    private final UUID leaderboardId = UUID.randomUUID();
    private final String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(leaderboardId);
    private AuditStreamTrimWorker worker;

    @BeforeAll
    static void startRedis() {
        redis.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redis.stop();
    }

    @BeforeEach
    void setUp() {
        when(leaderLock.isLeader()).thenReturn(true);
        when(leaderboardRepository.findAllIds()).thenReturn(List.of(leaderboardId));
        worker = new AuditStreamTrimWorker(leaderboardRepository, redisTemplate, leaderLock);
    }

    @Test
    void pendingEntries_trimsStrictlyBelowOldestPendingId() {
        appendEntries(5);
        createGroup();
        readAsRelay(3);
        acknowledge("1-0", "2-0");

        worker.trimAll();

        assertEquals(List.of("3-0", "4-0", "5-0"), remainingIds());
    }

    @Test
    void emptyPel_trimsBelowLastDeliveredId_andKeepsUndelivered() {
        appendEntries(5);
        createGroup();
        readAsRelay(3);
        acknowledge("1-0", "2-0", "3-0");

        worker.trimAll();

        assertEquals(List.of("3-0", "4-0", "5-0"), remainingIds());
    }

    @Test
    void relayReadBetweenStatusReads_keepsUnackedEntries() {
        appendEntries(5);
        createGroup();
        readAsRelay(3);
        acknowledge("1-0", "2-0", "3-0");
        AuditStreamTrimWorker interleaved = new AuditStreamTrimWorker(
                leaderboardRepository, relayReadsAfterFirstStatusRead(2), leaderLock);

        interleaved.trimAll();

        assertEquals(List.of("4-0", "5-0"), remainingIds());
    }

    @Test
    void noGroup_trimsNothing() {
        appendEntries(3);

        assertDoesNotThrow(worker::trimAll);

        assertEquals(List.of("1-0", "2-0", "3-0"), remainingIds());
    }

    @Test
    void noStream_trimsNothing() {
        assertDoesNotThrow(worker::trimAll);

        assertFalse(redisTemplate.hasKey(streamKey));
    }

    @Test
    void nonLeader_doesNothing() {
        when(leaderLock.isLeader()).thenReturn(false);
        appendEntries(5);
        createGroup();
        readAsRelay(3);
        acknowledge("1-0", "2-0", "3-0");

        worker.trimAll();

        assertEquals(List.of("1-0", "2-0", "3-0", "4-0", "5-0"), remainingIds());
        verifyNoInteractions(leaderboardRepository);
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate relayReadsAfterFirstStatusRead(int count) {
        StreamOperations<String, Object, Object> streamOps = spy(redisTemplate.opsForStream());
        AtomicBoolean interleaved = new AtomicBoolean();
        Answer<Object> readThenInterleave = invocation -> {
            Object result = invocation.callRealMethod();
            if (interleaved.compareAndSet(false, true)) {
                readAsRelay(count);
            }
            return result;
        };
        doAnswer(readThenInterleave).when(streamOps).pending(any(String.class), any(String.class));
        doAnswer(readThenInterleave).when(streamOps).groups(any(String.class));
        StringRedisTemplate template = spy(redisTemplate);
        doReturn(streamOps).when(template).opsForStream();
        return template;
    }

    private void appendEntries(int count) {
        for (int i = 1; i <= count; i++) {
            redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(streamKey)
                    .withId(RecordId.of(i + "-0"))
                    .ofMap(Map.of("n", String.valueOf(i))));
        }
    }

    private void createGroup() {
        redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), AuditRelayWorker.GROUP);
    }

    private void readAsRelay(int count) {
        redisTemplate.opsForStream().read(
                Consumer.from(AuditRelayWorker.GROUP, "relay-test"),
                StreamReadOptions.empty().count(count),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
    }

    private void acknowledge(String... ids) {
        redisTemplate.opsForStream().acknowledge(streamKey, AuditRelayWorker.GROUP, ids);
    }

    private List<String> remainingIds() {
        return redisTemplate.opsForStream().range(streamKey, Range.unbounded()).stream()
                .map(MapRecord::getId)
                .map(RecordId::getValue)
                .toList();
    }
}

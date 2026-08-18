package com.jake.realtimeapi.events.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.infra.config.AuditTopicConfig;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Redis audit stream(outbox)에 쌓인 기록을 Kafka로 옮긴다.
 *
 * <p>흐름: 리더보드마다 스트림이 하나이므로 전부 순회하며, 각 스트림에서
 * "받았지만 아직 확인 못 한 것(PEL)"을 먼저 재처리한 뒤 새 기록을 빌 때까지 읽어 Kafka로 produce한다.
 *
 * <p>핵심 순서: <b>produce 성공을 확인한 뒤에만 XACK</b>. 반대로 하면 XACK 직후 produce 전에 죽었을 때
 * PEL에도 없고 Kafka에도 없는 유실이 생긴다. 이 순서 덕에 최악의 경우는 "produce는 됐는데 XACK 전에 죽어
 * 다음 틱에 다시 보냄"(중복)이고, 중복은 소비자 멱등으로 흡수한다.
 */
@Component
@ConditionalOnProperty(name = "events.relay.enabled", havingValue = "true")
public class AuditRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(AuditRelayWorker.class);

    // 트림 워커·상태 조회도 같은 그룹을 참조한다. 이름은 LeaderboardRedisKeyFactory가 단일 소유.
    public static final String GROUP = LeaderboardRedisKeyFactory.AUDIT_RELAY_GROUP;
    private static final String SCHEMA_VERSION_HEADER = "schema-version";
    private static final byte[] SCHEMA_VERSION = "1".getBytes(StandardCharsets.UTF_8);

    private final LeaderboardRepository leaderboardRepository;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final Duration claimMinIdle;
    private final Map<UUID, String> claimCursors = new ConcurrentHashMap<>();

    // 컨슈머 이름. compose에서 hostname을 고정했으므로 재생성돼도 같은 이름을 재사용한다.
    // 이름이 바뀌면 이전 이름이 잡고 있던 PEL이 주인 없이 남는다.
    private final String consumerName;

    public AuditRelayWorker(
            LeaderboardRepository leaderboardRepository,
            StringRedisTemplate redisTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${events.relay.batch-size:500}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${events.relay.claim-min-idle-ms:600000}") long claimMinIdleMs
    ) {
        this.leaderboardRepository = leaderboardRepository;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.claimMinIdle = Duration.ofMillis(claimMinIdleMs);
        String host = System.getenv("HOSTNAME");
        this.consumerName = (host == null || host.isBlank()) ? "relay-local" : host;
    }

    @Scheduled(fixedDelayString = "${events.relay.delay-ms:5000}")
    public void relayAll() {
        for (UUID leaderboardId : leaderboardRepository.findAllIds()) {
            try {
                relayOne(leaderboardId);
            } catch (DataAccessException ex) {
                // 한 스트림의 실패가 나머지 리더보드 처리를 막지 않게 격리한다. 다음 틱에 재시도된다.
                log.error("relay failed leaderboardId={}", leaderboardId, ex);
            }
        }
    }

    private void relayOne(UUID leaderboardId) {
        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(leaderboardId);
        ensureGroup(streamKey);

        // 1) PEL 재처리: 받았지만 XACK 못 한 것(크래시로 멈춘 것)을 먼저 다시 보낸다.
        //    ReadOffset "0" = 이 컨슈머의 PEL을 처음부터 재조회한다(새 기록이 아니라).
        int recovered = relayBatch(leaderboardId, streamKey, ReadOffset.from("0"), batchSize);

        // 2) 다른 릴레이가 남긴 오래된 PEL을 현재 릴레이로 인계한다.
        int remainingRecoveryCapacity = batchSize - recovered;
        if (remainingRecoveryCapacity > 0) {
            relayClaimedBatch(leaderboardId, streamKey, remainingRecoveryCapacity);
        }

        // 3) 새 기록: 빌 때까지 반복해서 읽는다. 한 번에 가져오는 수를 고정하면
        //    유입이 그보다 많을 때 밀린 양이 줄지 않는다.
        while (relayBatch(leaderboardId, streamKey, ReadOffset.lastConsumed(), batchSize) > 0) {
            // 계속 드레인
        }
    }

    /** 한 배치를 읽어 모두 produce한 뒤, 성공한 것만 XACK 한다. 읽은 건수를 반환한다. */
    private int relayBatch(UUID leaderboardId, String streamKey, ReadOffset offset, int count) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(count),
                StreamOffset.create(streamKey, offset)
        );
        if (records == null || records.isEmpty()) {
            return 0;
        }

        List<AuditStreamRecord> batch = records.stream()
                .map(record -> new AuditStreamRecord(record.getId().getValue(), record.getValue()))
                .toList();
        return publishAndAcknowledge(leaderboardId, streamKey, batch);
    }

    private int relayClaimedBatch(UUID leaderboardId, String streamKey, int count) {
        ClaimedMessages<byte[], byte[]> claimed = autoClaim(
                streamKey,
                claimCursors.getOrDefault(leaderboardId, "0-0"),
                count
        );
        if (claimed == null) {
            return 0;
        }

        if ("0-0".equals(claimed.getId())) {
            claimCursors.remove(leaderboardId);
        } else {
            claimCursors.put(leaderboardId, claimed.getId());
        }

        List<AuditStreamRecord> batch = claimed.getMessages().stream()
                .map(this::toAuditStreamRecord)
                .toList();
        return publishAndAcknowledge(leaderboardId, streamKey, batch);
    }

    @SuppressWarnings("unchecked")
    private ClaimedMessages<byte[], byte[]> autoClaim(String streamKey, String startId, int count) {
        return redisTemplate.execute((RedisCallback<ClaimedMessages<byte[], byte[]>>) connection -> {
            RedisClusterAsyncCommands<byte[], byte[]> commands =
                    (RedisClusterAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
            XAutoClaimArgs<byte[]> args = new XAutoClaimArgs<byte[]>()
                    .consumer(io.lettuce.core.Consumer.from(bytes(GROUP), bytes(consumerName)))
                    .minIdleTime(claimMinIdle)
                    .startId(startId)
                    .count(count);
            try {
                return commands.xautoclaim(bytes(streamKey), args).get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DataAccessResourceFailureException("XAUTOCLAIM interrupted", exception);
            } catch (ExecutionException exception) {
                throw new DataAccessResourceFailureException("XAUTOCLAIM failed", exception.getCause());
            }
        });
    }

    private AuditStreamRecord toAuditStreamRecord(StreamMessage<byte[], byte[]> message) {
        Map<Object, Object> fields = new LinkedHashMap<>();
        message.getBody().forEach((key, value) -> fields.put(new String(key, StandardCharsets.UTF_8),
                new String(value, StandardCharsets.UTF_8)));
        return new AuditStreamRecord(message.getId(), fields);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private int publishAndAcknowledge(
            UUID leaderboardId,
            String streamKey,
            List<AuditStreamRecord> records
    ) {
        if (records.isEmpty()) {
            return 0;
        }

        // 먼저 전부 보낸다(응답은 기다리지 않는다). key = leaderboardId → 같은 리더보드는 같은 파티션.
        String partitionKey = leaderboardId.toString();
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(records.size());
        for (AuditStreamRecord record : records) {
            ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                    AuditTopicConfig.AUDIT_TOPIC, partitionKey, toJson(record.id(), record.fields()));
            producerRecord.headers().add(SCHEMA_VERSION_HEADER, SCHEMA_VERSION);
            futures.add(kafkaTemplate.send(producerRecord));
        }
        kafkaTemplate.flush();

        // 성공을 확인한 것만 XACK 한다. 실패한 것은 PEL에 남겨 다음 틱에 재시도한다.
        List<String> acknowledged = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            try {
                futures.get(i).get();
                acknowledged.add(records.get(i).id());
            } catch (Exception ex) {
                log.error("relay produce failed streamKey={} entryId={}", streamKey, records.get(i).id(), ex);
            }
        }
        if (!acknowledged.isEmpty()) {
            redisTemplate.opsForStream().acknowledge(streamKey, GROUP, acknowledged.toArray(new String[0]));
        }
        return records.size();
    }

    private record AuditStreamRecord(String id, Map<Object, Object> fields) {
    }

    private void ensureGroup(String streamKey) {
        try {
            // ReadOffset "0": 그룹 생성 전에 이미 쌓인 기록도 처음부터 배달받는다. latest()로 만들면 이전 것을 놓친다.
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), GROUP);
        } catch (DataAccessException ex) {
            // 이미 그룹이 있으면(BUSYGROUP) 정상 흐름이다. 스트림이 아직 없으면 옮길 것도 없으니 다음 틱에 다시 시도한다.
            log.debug("relay group ensure skipped streamKey={} ({})", streamKey, ex.getMessage());
        }
    }

    private String toJson(String eventId, Map<Object, Object> fields) {
        // 스트림 필드는 문자열 쌍이다. 순서를 유지해 직렬화한다.
        // eventId(스트림 엔트리 ID)를 함께 실어 컨슈머가 (leaderboardId, eventId)로 전송 중복을 걸러낸다.
        // 엔트리 ID는 한 리더보드 스트림 안에서만 고유하므로 leaderboardId(=Kafka 키)와 짝으로만 유일하다.
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("eventId", eventId);
        fields.forEach((k, v) -> ordered.put(String.valueOf(k), String.valueOf(v)));
        try {
            return objectMapper.writeValueAsString(ordered);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // 필드가 문자열 맵이라 실질적으로 발생하지 않지만, 발생 시 이 기록은 배치를 막지 않도록 예외로 올린다.
            throw new IllegalStateException("failed to serialize audit record: " + ordered, ex);
        }
    }
}

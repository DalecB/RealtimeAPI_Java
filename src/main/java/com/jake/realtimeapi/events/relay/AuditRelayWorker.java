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

@Component
@ConditionalOnProperty(name = "events.relay.enabled", havingValue = "true")
public class AuditRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(AuditRelayWorker.class);

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
    private final AuditRelayLeaderLock leaderLock;

    private final String consumerName;

    public AuditRelayWorker(
            LeaderboardRepository leaderboardRepository,
            StringRedisTemplate redisTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${events.relay.batch-size:500}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${events.relay.claim-min-idle-ms:600000}") long claimMinIdleMs,
            @org.springframework.beans.factory.annotation.Value("${events.relay.consumer-name}") String consumerName,
            AuditRelayLeaderLock leaderLock
    ) {
        this.leaderboardRepository = leaderboardRepository;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.claimMinIdle = Duration.ofMillis(claimMinIdleMs);
        this.consumerName = consumerName;
        this.leaderLock = leaderLock;
    }

    @Scheduled(fixedDelayString = "${events.relay.delay-ms:5000}", scheduler = AuditRelaySchedulerConfig.SCHEDULER)
    public void relayAll() {
        // 리더보드별 순서는 활성 릴레이가 하나일 때만 지켜지므로 락을 쥔 인스턴스만 옮긴다.
        if (!leaderLock.isLeader()) {
            log.debug("relay skipped not-leader consumer={}", consumerName);
            return;
        }
        for (UUID leaderboardId : leaderboardRepository.findAllIds()) {
            try {
                relayOne(leaderboardId);
            } catch (RuntimeException ex) {
                log.error("relay failed leaderboardId={}", leaderboardId, ex);
            }
        }
    }

    private void relayOne(UUID leaderboardId) {
        String streamKey = LeaderboardRedisKeyFactory.auditStreamKey(leaderboardId);
        ensureGroup(streamKey);

        // XREADGROUP에 "0"을 주면 새 기록이 아니라 이 컨슈머의 PEL을 처음부터 돌려준다.
        int recovered = relayBatch(leaderboardId, streamKey, ReadOffset.from("0"), batchSize);

        int remainingRecoveryCapacity = batchSize - recovered;
        if (remainingRecoveryCapacity > 0) {
            relayClaimedBatch(leaderboardId, streamKey, remainingRecoveryCapacity);
        }

        // 틱당 한 배치만 읽으면 유입이 배치보다 많을 때 밀린 양이 줄지 않아 빌 때까지 읽는다.
        while (relayBatch(leaderboardId, streamKey, ReadOffset.lastConsumed(), batchSize) > 0) {
        }
    }

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

        // key를 leaderboardId로 둬야 같은 리더보드가 한 파티션에 모여 순서가 지켜진다.
        String partitionKey = leaderboardId.toString();
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(records.size());
        for (AuditStreamRecord record : records) {
            ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                    AuditTopicConfig.AUDIT_TOPIC, partitionKey, toJson(record.id(), record.fields()));
            producerRecord.headers().add(SCHEMA_VERSION_HEADER, SCHEMA_VERSION);
            futures.add(kafkaTemplate.send(producerRecord));
        }
        kafkaTemplate.flush();

        // produce 성공을 확인한 것만 XACK한다. 먼저 XACK하면 produce 전에 죽었을 때 PEL에도 Kafka에도 없이 유실된다.
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
        if (acknowledged.size() < records.size()) {
            // 실패 뒤에도 계속 읽으면 장애 동안 틱이 유입이 멈출 때까지 끝나지 않으므로, 이 리더보드는 다음 틱에 PEL부터 다시 시도한다.
            throw new IllegalStateException("relay batch publish failed streamKey=" + streamKey
                    + " failed=" + (records.size() - acknowledged.size()) + "/" + records.size());
        }
        return records.size();
    }

    private record AuditStreamRecord(String id, Map<Object, Object> fields) {
    }

    private void ensureGroup(String streamKey) {
        try {
            // latest()로 만들면 그룹 생성 전에 쌓인 기록을 놓치므로 "0"부터 받는다.
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), GROUP);
        } catch (DataAccessException ex) {
            // BUSYGROUP(이미 있음)과 스트림 없음은 정상이라 삼킨다. 스트림이 생기면 다음 틱에 만든다.
            log.debug("relay group ensure skipped streamKey={} ({})", streamKey, ex.getMessage());
        }
    }

    private String toJson(String eventId, Map<Object, Object> fields) {
        // 엔트리 ID는 한 리더보드 스트림 안에서만 고유해 컨슈머는 leaderboardId(Kafka 키)와 짝으로 중복을 거른다.
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("eventId", eventId);
        fields.forEach((k, v) -> ordered.put(String.valueOf(k), String.valueOf(v)));
        try {
            return objectMapper.writeValueAsString(ordered);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize audit record: " + ordered, ex);
        }
    }
}

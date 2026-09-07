package com.jake.realtimeapi.events.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.infra.config.AuditTopicConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * audit 토픽을 소비해 원본 이벤트를 PostgreSQL에 적재한다(추이 집계·이상 탐지·아카이브의 소스).
 *
 * <p>배치 리스너: 컨테이너가 한 poll 분량을 넘겨주고, 이 메서드가 정상 반환하면 오프셋을 커밋한다(처리 후 커밋).
 * 처리 중 죽으면 커밋 안 된 지점부터 다시 읽어(중복) 유실을 막고, 중복은 DB UNIQUE로 흡수한다.
 */
@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);
    private static final int PARSE_ATTEMPTS = 3;

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;
    private final DeadLetterPublishingRecoverer deadLetterPublishingRecoverer;
    private final long parseRetryDelayMs;

    public AuditEventConsumer(
            AuditEventRepository auditEventRepository,
            ObjectMapper objectMapper,
            DeadLetterPublishingRecoverer deadLetterPublishingRecoverer,
            @Value("${events.consumer.parse-retry-delay-ms:3000}") long parseRetryDelayMs
    ) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
        this.deadLetterPublishingRecoverer = deadLetterPublishingRecoverer;
        this.parseRetryDelayMs = parseRetryDelayMs;
    }

    @KafkaListener(
            id = "audit-trend-consumer",
            topics = AuditTopicConfig.AUDIT_TOPIC,
            groupId = AuditTopicConfig.AUDIT_CONSUMER_GROUP
    )
    public void consume(List<ConsumerRecord<String, String>> records) {
        List<AuditEventRepository.AuditEventRow> rows = new ArrayList<>(records.size());
        List<FailedRecord> failures = parse(records, rows);
        for (int attempt = 1; attempt < PARSE_ATTEMPTS && !failures.isEmpty(); attempt++) {
            waitBeforeRetry();
            failures = retry(failures, rows);
        }

        if (!rows.isEmpty()) {
            auditEventRepository.insertIgnoringDuplicates(rows);
        }

        for (FailedRecord failure : failures) {
            deadLetterPublishingRecoverer.accept(failure.record(), failure.exception());
            log.error(
                    "audit event sent to DLT topic={} partition={} offset={}",
                    failure.record().topic(),
                    failure.record().partition(),
                    failure.record().offset(),
                    failure.exception()
            );
        }
    }

    private List<FailedRecord> parse(
            List<ConsumerRecord<String, String>> records,
            List<AuditEventRepository.AuditEventRow> rows
    ) {
        List<FailedRecord> failures = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                rows.add(toRow(record.key(), record.value()));
            } catch (RuntimeException ex) {
                failures.add(new FailedRecord(record, ex));
            }
        }
        return failures;
    }

    private List<FailedRecord> retry(
            List<FailedRecord> failures,
            List<AuditEventRepository.AuditEventRow> rows
    ) {
        List<ConsumerRecord<String, String>> records = failures.stream()
                .map(FailedRecord::record)
                .toList();
        return parse(records, rows);
    }

    private void waitBeforeRetry() {
        try {
            Thread.sleep(parseRetryDelayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("audit event parse retry interrupted", ex);
        }
    }

    private AuditEventRepository.AuditEventRow toRow(String leaderboardId, String json) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("malformed audit event json", ex);
        }
        String eventId = node.required("eventId").asText();
        String eventType = node.required("type").asText();
        if (!eventType.equals("new") && !eventType.equals("conflict")) {
            throw new IllegalArgumentException("unsupported audit event type: " + eventType);
        }
        return new AuditEventRepository.AuditEventRow(
                UUID.fromString(leaderboardId),
                eventId,
                eventTimeFromEntryId(eventId),
                eventType,
                Long.parseLong(node.required("userId").asText()),
                Long.parseLong(node.required("delta").asText()),
                Long.parseLong(node.required("apiKeyId").asText()),
                UUID.fromString(node.required("idempotencyKey").asText()).toString()
        );
    }

    // 스트림 엔트리 ID "<ms>-<seq>"의 앞부분이 이벤트 발생 시각(Redis 서버 시계)이다.
    private Instant eventTimeFromEntryId(String eventId) {
        int dash = eventId.indexOf('-');
        long epochMillis = Long.parseLong(dash < 0 ? eventId : eventId.substring(0, dash));
        return Instant.ofEpochMilli(epochMillis);
    }

    private record FailedRecord(ConsumerRecord<String, String> record, RuntimeException exception) {
    }
}

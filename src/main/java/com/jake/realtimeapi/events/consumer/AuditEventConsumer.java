package com.jake.realtimeapi.events.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.infra.config.AuditTopicConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
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

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public AuditEventConsumer(AuditEventRepository auditEventRepository, ObjectMapper objectMapper) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "audit-trend-consumer", topics = AuditTopicConfig.AUDIT_TOPIC, groupId = "audit-trend")
    public void consume(List<ConsumerRecord<String, String>> records) {
        List<AuditEventRepository.AuditEventRow> rows = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                rows.add(toRow(record.key(), record.value()));
            } catch (RuntimeException ex) {
                // 파싱 불가한 한 건이 배치 전체를 막지 않게 건너뛴다(로그만). 스키마 드리프트 조기 발견용.
                log.error("audit event parse failed key={} value={}", record.key(), record.value(), ex);
            }
        }
        if (!rows.isEmpty()) {
            auditEventRepository.insertIgnoringDuplicates(rows);
        }
    }

    private AuditEventRepository.AuditEventRow toRow(String leaderboardId, String json) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("malformed audit event json", ex);
        }
        String eventId = node.get("eventId").asText();
        return new AuditEventRepository.AuditEventRow(
                UUID.fromString(leaderboardId),
                eventId,
                eventTimeFromEntryId(eventId),
                node.get("type").asText(),
                node.get("userId").asLong(),
                node.get("delta").asLong(),
                node.get("apiKeyId").asLong(),
                node.get("idempotencyKey").asText()
        );
    }

    // 스트림 엔트리 ID "<ms>-<seq>"의 앞부분이 이벤트 발생 시각(Redis 서버 시계)이다.
    private Instant eventTimeFromEntryId(String eventId) {
        int dash = eventId.indexOf('-');
        long epochMillis = Long.parseLong(dash < 0 ? eventId : eventId.substring(0, dash));
        return Instant.ofEpochMilli(epochMillis);
    }
}

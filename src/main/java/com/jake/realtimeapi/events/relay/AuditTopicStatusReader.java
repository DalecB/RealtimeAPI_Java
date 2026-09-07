package com.jake.realtimeapi.events.relay;

import com.jake.realtimeapi.infra.config.AuditTopicConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * audit 토픽에 실제로 도착한 메시지 수를 Kafka에서 직접 읽는다.
 *
 * <p>앱이 센 카운터가 아니라 토픽의 offset을 읽으므로, relay에 버그가 있어 실제로 produce가 안 됐다면
 * 이 값도 안 움직인다 — "Kafka에 들어갔다"를 앱과 독립적으로 확인한다.
 */
@Component
public class AuditTopicStatusReader {

    private static final Logger log = LoggerFactory.getLogger(AuditTopicStatusReader.class);
    private static final String TOPIC = AuditTopicConfig.AUDIT_TOPIC;
    private static final String DLT_TOPIC = AuditTopicConfig.AUDIT_DLT_TOPIC;

    private final AdminClient adminClient;

    public AuditTopicStatusReader(AdminClient adminClient) {
        this.adminClient = adminClient;
    }

    /** 원본 토픽의 발행량·보관량·consumer lag과 DLT의 발행량·보관량을 읽는다. */
    public AuditTopicStatus read() {
        try {
            Map<String, TopicDescription> descriptions = adminClient.describeTopics(List.of(TOPIC, DLT_TOPIC))
                    .allTopicNames().get();

            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
            descriptions.forEach((topic, description) -> {
                description.partitions().forEach(p -> {
                    TopicPartition tp = new TopicPartition(topic, p.partition());
                    latest.put(tp, OffsetSpec.latest());
                    earliest.put(tp, OffsetSpec.earliest());
                });
            });

            Map<TopicPartition, ListOffsetsResultInfo> latestOffsets = adminClient.listOffsets(latest).all().get();
            Map<TopicPartition, ListOffsetsResultInfo> earliestOffsets = adminClient.listOffsets(earliest).all().get();
            long produced = sumOffsets(latestOffsets, TOPIC);
            long dltProduced = sumOffsets(latestOffsets, DLT_TOPIC);
            return new AuditTopicStatus(
                    produced,
                    produced - sumOffsets(earliestOffsets, TOPIC),
                    consumerLag(latestOffsets, earliestOffsets),
                    dltProduced,
                    dltProduced - sumOffsets(earliestOffsets, DLT_TOPIC)
            );
        } catch (Exception ex) {
            log.warn("audit topic status read failed", ex);
            return new AuditTopicStatus(-1L, -1L, -1L, -1L, -1L);
        }
    }

    private long consumerLag(
            Map<TopicPartition, ListOffsetsResultInfo> latestOffsets,
            Map<TopicPartition, ListOffsetsResultInfo> earliestOffsets
    ) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                .listConsumerGroupOffsets(AuditTopicConfig.AUDIT_CONSUMER_GROUP)
                .partitionsToOffsetAndMetadata()
                .get();

        long lag = 0L;
        for (var entry : latestOffsets.entrySet()) {
            if (!TOPIC.equals(entry.getKey().topic())) {
                continue;
            }
            OffsetAndMetadata offset = committed.get(entry.getKey());
            long consumed = offset == null ? earliestOffsets.get(entry.getKey()).offset() : offset.offset();
            lag += Math.max(0L, entry.getValue().offset() - consumed);
        }
        return lag;
    }

    private long sumOffsets(Map<TopicPartition, ListOffsetsResultInfo> offsets, String topic) {
        return offsets.entrySet().stream()
                .filter(entry -> topic.equals(entry.getKey().topic()))
                .mapToLong(entry -> entry.getValue().offset())
                .sum();
    }

    public record AuditTopicStatus(
            long totalMessages,
            long retained,
            long consumerLag,
            long dltTotalMessages,
            long dltRetained
    ) {
    }
}

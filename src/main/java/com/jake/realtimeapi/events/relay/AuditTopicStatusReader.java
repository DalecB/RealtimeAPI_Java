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

    private final AdminClient adminClient;

    public AuditTopicStatusReader(AdminClient adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * @return 지금까지 produce된 총 건수(end offset 합)와 현재 토픽에 남아 있는 건수(end-begin 합).
     *         조회 실패 시 둘 다 -1.
     */
    public AuditTopicStatus read() {
        try {
            TopicDescription description = adminClient.describeTopics(List.of(TOPIC))
                    .allTopicNames().get().get(TOPIC);

            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
            description.partitions().forEach(p -> {
                TopicPartition tp = new TopicPartition(TOPIC, p.partition());
                latest.put(tp, OffsetSpec.latest());
                earliest.put(tp, OffsetSpec.earliest());
            });

            Map<TopicPartition, ListOffsetsResultInfo> latestOffsets = adminClient.listOffsets(latest).all().get();
            Map<TopicPartition, ListOffsetsResultInfo> earliestOffsets = adminClient.listOffsets(earliest).all().get();
            long produced = sumOffsets(latestOffsets);
            long retained = produced - sumOffsets(earliestOffsets);
            return new AuditTopicStatus(produced, retained, consumerLag(latestOffsets, earliestOffsets));
        } catch (Exception ex) {
            log.warn("audit topic status read failed", ex);
            return new AuditTopicStatus(-1L, -1L, -1L);
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
            OffsetAndMetadata offset = committed.get(entry.getKey());
            long consumed = offset == null ? earliestOffsets.get(entry.getKey()).offset() : offset.offset();
            lag += Math.max(0L, entry.getValue().offset() - consumed);
        }
        return lag;
    }

    private long sumOffsets(Map<TopicPartition, ListOffsetsResultInfo> offsets) {
        return offsets.values().stream().mapToLong(ListOffsetsResultInfo::offset).sum();
    }

    public record AuditTopicStatus(long totalMessages, long retained, long consumerLag) {
    }
}

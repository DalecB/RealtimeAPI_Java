package com.jake.realtimeapi.events.relay;

import com.jake.realtimeapi.events.consumer.AuditConsumerStatus;
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

// 앱 카운터가 아니라 토픽 offset을 읽어야 relay 버그로 produce가 안 된 경우도 드러난다.
@Component
public class AuditTopicStatusReader {

    private static final Logger log = LoggerFactory.getLogger(AuditTopicStatusReader.class);
    private static final String TOPIC = AuditTopicConfig.AUDIT_TOPIC;
    private static final String DLT_TOPIC = AuditTopicConfig.AUDIT_DLT_TOPIC;

    private final AdminClient adminClient;
    private final AuditConsumerStatus consumerStatus;

    public AuditTopicStatusReader(AdminClient adminClient, AuditConsumerStatus consumerStatus) {
        this.adminClient = adminClient;
        this.consumerStatus = consumerStatus;
    }

    public AuditTopicStatus read() {
        AuditConsumerStatus.Snapshot consumer = consumerStatus.snapshot();
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
                    dltProduced - sumOffsets(earliestOffsets, DLT_TOPIC),
                    consumer.state(),
                    consumer.retryDurationSeconds(),
                    consumer.retryAttempts(),
                    consumer.ready()
            );
        } catch (Exception ex) {
            log.warn("audit topic status read failed", ex);
            return new AuditTopicStatus(
                    -1L, -1L, -1L, -1L, -1L,
                    consumer.state(), consumer.retryDurationSeconds(), consumer.retryAttempts(), consumer.ready()
            );
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
            long dltRetained,
            String consumerState,
            long retryDurationSeconds,
            long retryAttempts,
            boolean consumerReady
    ) {
    }
}

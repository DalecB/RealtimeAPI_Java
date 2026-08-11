package com.jake.realtimeapi.infra.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * 감사 로그 토픽을 애플리케이션 기동 시 생성한다.
 * 브로커의 auto-create를 껐으므로(docker-compose), NewTopic 빈이 없으면 토픽이 만들어지지 않는다.
 * 리텐션·정리 정책을 여기서 명시하는 것이 토픽을 코드로 만드는 이유다.
 */
@Configuration
public class AuditTopicConfig {

    /** relay가 produce할 때, 컨슈머가 subscribe할 때 참조하는 토픽 이름. */
    public static final String AUDIT_TOPIC = "lb-audit-events";
    public static final String AUDIT_CONSUMER_GROUP = "audit-trend";

    // 파티션 3: 처리량이 아니라 한 컨슈머 그룹에서 병렬로 돌릴 인스턴스 수의 상한이다.
    // 파티션은 줄일 수 없고, 늘리면 key→partition 매핑이 바뀌어 순서 보장이 깨진다.
    private static final int PARTITIONS = 3;
    private static final short REPLICATION = 1; // 단일 브로커라 강제. 프로덕션 기준은 SPIKE-001.

    private static final String RETENTION_MS = Long.toString(30L * 24 * 60 * 60 * 1000); // 30일
    private static final String RETENTION_BYTES = Long.toString(1024L * 1024 * 1024);    // 1GB (로컬 기준)

    @Bean
    public KafkaAdmin.NewTopics auditTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(AUDIT_TOPIC)
                        .partitions(PARTITIONS)
                        .replicas(REPLICATION)
                        .config(TopicConfig.RETENTION_MS_CONFIG, RETENTION_MS)
                        .config(TopicConfig.RETENTION_BYTES_CONFIG, RETENTION_BYTES)
                        // compact는 key당 마지막 값만 남겨 감사 로그를 파괴한다. delete 유지.
                        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                        .build()
        );
    }

    /** 토픽 offset 조회용. KafkaAdmin의 접속 설정을 재사용해 하나만 만들어 공유한다(스레드 안전). */
    @Bean
    public AdminClient kafkaAdminClient(KafkaAdmin kafkaAdmin) {
        return AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }
}

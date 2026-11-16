package com.jake.realtimeapi.events.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

// DEFAULT 파티션이 없어 그날 파티션이 없으면 INSERT가 실패하므로, 릴레이·스냅샷 워커와 달리 opt-in이 아니다.
@Component
public class AuditEventPartitionJob {

    // 여러 인스턴스의 CREATE TABLE IF NOT EXISTS끼리 카탈로그 경합으로 실패할 수 있어 xact 락으로 직렬화한다(뒤 인스턴스는 대기 후 no-op).
    private static final long LOCK_KEY = 7_000_000_001L;
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int retentionDays;
    private final int aheadDays;

    public AuditEventPartitionJob(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            @Value("${events.consumer.partition-retention-days:30}") int retentionDays,
            @Value("${events.consumer.partition-ahead-days:3}") int aheadDays
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.retentionDays = retentionDays;
        this.aheadDays = aheadDays;
    }

    // Kafka 리스너 컨테이너는 모든 빈 생성 뒤 lifecycle 시작에 뜨므로 @PostConstruct면 첫 INSERT 전에 오늘 파티션이 있다.
    @PostConstruct
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void maintainToday() {
        maintain(LocalDate.now(ZoneOffset.UTC));
    }

    void maintain(LocalDate today) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");

            for (int i = 0; i <= aheadDays; i++) {
                LocalDate day = today.plusDays(i);
                jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS audit_events_" + day.format(SUFFIX)
                        + " PARTITION OF audit_events FOR VALUES FROM ('" + day + " 00:00:00+00')"
                        + " TO ('" + day.plusDays(1) + " 00:00:00+00')");
            }

            LocalDate oldestKept = today.minusDays(retentionDays);
            for (String partition : partitions()) {
                LocalDate day = LocalDate.parse(partition.substring("audit_events_".length()), SUFFIX);
                if (day.isBefore(oldestKept)) {
                    jdbcTemplate.execute("DROP TABLE IF EXISTS " + partition);
                }
            }
        });
    }

    private List<String> partitions() {
        return jdbcTemplate.queryForList("""
                SELECT c.relname
                FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'audit_events'::regclass
                  AND c.relname ~ '^audit_events_[0-9]{8}$'
                """, String.class);
    }
}

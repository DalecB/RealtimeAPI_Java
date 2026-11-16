package com.jake.realtimeapi.events.consumer;

import com.jake.realtimeapi.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditEventPartitionTest {

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AuditEventQueryRepository auditEventQueryRepository;

    @Autowired
    private AuditEventPartitionJob auditEventPartitionJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void duplicateInsert_isStoredOnce_andShowsInTrend() {
        UUID leaderboardId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuditEventRepository.AuditEventRow row = row(leaderboardId, now);

        auditEventRepository.insertIgnoringDuplicates(List.of(row));
        auditEventRepository.insertIgnoringDuplicates(List.of(row));

        List<AuditEventQueryRepository.TrendBucket> trend = auditEventQueryRepository.trend(
                leaderboardId, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), "5m");
        assertEquals(1, trend.size());
        assertEquals(1L, trend.get(0).eventCount());
        assertEquals(5L, trend.get(0).deltaSum());
    }

    @Test
    void maintain_createsAheadPartitions_andDropsOnlyDaysOlderThanRetention() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        createPartition(today.minusDays(31));
        createPartition(today.minusDays(30));
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + name(today.plusDays(3)));

        auditEventPartitionJob.maintain(today);

        List<String> partitions = partitions();
        assertFalse(partitions.contains(name(today.minusDays(31))));
        assertTrue(partitions.contains(name(today.minusDays(30))));
        for (int i = 0; i <= 3; i++) {
            assertTrue(partitions.contains(name(today.plusDays(i))));
        }

        auditEventPartitionJob.maintain(today);
        assertEquals(partitions.size(), partitions().size());
    }

    @Test
    void insertIntoDayWithoutPartition_isDataIntegrityViolation() {
        // 컨슈머 에러 핸들러는 이 예외를 영구 오류로 분류해 컨테이너를 멈춘다(오프셋 미커밋).
        Instant farFuture = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        assertThrows(DataIntegrityViolationException.class, () ->
                auditEventRepository.insertIgnoringDuplicates(List.of(row(UUID.randomUUID(), farFuture))));
    }

    private AuditEventRepository.AuditEventRow row(UUID leaderboardId, Instant eventTime) {
        return new AuditEventRepository.AuditEventRow(
                leaderboardId, eventTime.toEpochMilli() + "-0", eventTime, "new",
                1L, 5L, 7L, UUID.randomUUID().toString());
    }

    private void createPartition(LocalDate day) {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + name(day)
                + " PARTITION OF audit_events FOR VALUES FROM ('" + day + " 00:00:00+00')"
                + " TO ('" + day.plusDays(1) + " 00:00:00+00')");
    }

    private List<String> partitions() {
        return jdbcTemplate.queryForList(
                "SELECT inhrelid::regclass::text FROM pg_inherits WHERE inhparent = 'audit_events'::regclass",
                String.class);
    }

    private static String name(LocalDate day) {
        return "audit_events_" + day.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}

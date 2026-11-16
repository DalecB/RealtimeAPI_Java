package com.jake.realtimeapi.events.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AuditEventRepository {

    // 파티션 테이블 UNIQUE에는 파티션 키가 들어가야 해서 event_time을 넣는다. event_id에서만 계산되므로 중복 판정은 그대로다.
    private static final String INSERT = """
            INSERT INTO audit_events
                (leaderboard_id, event_id, event_time, event_type, user_id, delta, api_key_id, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (leaderboard_id, event_id, event_time) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuditEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertIgnoringDuplicates(List<AuditEventRow> rows) {
        jdbcTemplate.batchUpdate(INSERT, rows, rows.size(), (ps, row) -> {
            ps.setObject(1, row.leaderboardId());
            ps.setString(2, row.eventId());
            ps.setTimestamp(3, Timestamp.from(row.eventTime()));
            ps.setString(4, row.eventType());
            ps.setLong(5, row.userId());
            ps.setLong(6, row.delta());
            ps.setLong(7, row.apiKeyId());
            ps.setString(8, row.idempotencyKey());
        });
    }

    public record AuditEventRow(
            UUID leaderboardId,
            String eventId,
            Instant eventTime,
            String eventType,
            long userId,
            long delta,
            long apiKeyId,
            String idempotencyKey
    ) {
    }
}

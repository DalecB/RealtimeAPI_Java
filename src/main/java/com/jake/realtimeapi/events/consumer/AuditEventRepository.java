package com.jake.realtimeapi.events.consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kafka에서 읽은 원본 audit 이벤트를 PostgreSQL에 적재한다.
 *
 * <p>at-least-once라 같은 이벤트가 두 번 올 수 있으므로 (leaderboard_id, event_id) UNIQUE에 대해
 * ON CONFLICT DO NOTHING으로 중복을 DB가 무시하게 한다 — 집계가 두 번 반영되어 유저가 불이익을 받는 것을 막는다.
 */
@Repository
public class AuditEventRepository {

    private static final String INSERT = """
            INSERT INTO audit_events
                (leaderboard_id, event_id, event_time, event_type, user_id, delta, api_key_id, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (leaderboard_id, event_id) DO NOTHING
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

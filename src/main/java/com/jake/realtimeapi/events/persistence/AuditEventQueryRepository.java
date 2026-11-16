package com.jake.realtimeapi.events.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AuditEventQueryRepository {

    // 클라이언트는 버킷 코드만 보내고 interval 문자열은 여기서만 만든다(임의 주입 차단).
    private static final Map<String, String> BUCKET_INTERVALS = Map.of(
            "1m", "1 minute",
            "5m", "5 minutes",
            "15m", "15 minutes",
            "1h", "1 hour"
    );

    private final JdbcTemplate jdbcTemplate;

    public AuditEventQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_events", Long.class);
        return count == null ? 0L : count;
    }

    public List<UUID> leaderboardsWithEvents() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT leaderboard_id FROM audit_events ORDER BY leaderboard_id", UUID.class);
    }

    public List<RecentAuditEvent> recent(UUID leaderboardId, int limit) {
        String sql = """
                SELECT leaderboard_id, event_time, event_id, event_type, user_id, delta, api_key_id, idempotency_key
                FROM audit_events
                WHERE leaderboard_id = ?
                ORDER BY id DESC
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new RecentAuditEvent(
                        rs.getObject("leaderboard_id", UUID.class),
                        rs.getTimestamp("event_time").toInstant(),
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getLong("user_id"),
                        rs.getLong("delta"),
                        rs.getLong("api_key_id"),
                        rs.getString("idempotency_key")
                ),
                leaderboardId, limit
        );
    }

    public List<TrendBucket> trend(UUID leaderboardId, Instant from, Instant to, String bucketCode) {
        String interval = BUCKET_INTERVALS.get(bucketCode);
        if (interval == null) {
            throw new IllegalArgumentException("unsupported bucket: " + bucketCode);
        }
        // 누적 점수는 프론트가 러닝 합으로 그리므로 버킷별 delta 합·건수만 낸다.
        String sql = """
                SELECT date_bin(CAST(? AS interval), event_time, TIMESTAMPTZ '2000-01-01') AS bucket_start,
                       count(*)   AS event_count,
                       sum(delta) AS delta_sum
                FROM audit_events
                WHERE leaderboard_id = ? AND event_time >= ? AND event_time < ?
                GROUP BY bucket_start
                ORDER BY bucket_start
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new TrendBucket(
                        rs.getTimestamp("bucket_start").toInstant(),
                        rs.getLong("event_count"),
                        rs.getLong("delta_sum")
                ),
                interval, leaderboardId, Timestamp.from(from), Timestamp.from(to)
        );
    }

    public record TrendBucket(Instant bucketStart, long eventCount, long deltaSum) {
    }

    public record RecentAuditEvent(
            UUID leaderboardId,
            Instant eventTime,
            String eventId,
            String eventType,
            long userId,
            long delta,
            long apiKeyId,
            String idempotencyKey
    ) {
    }
}

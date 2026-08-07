package com.jake.realtimeapi.events.consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 적재된 원본 이벤트 조회 — 루프 확인용 총 건수와 리더보드별 점수 추이.
 */
@Repository
public class AuditEventQueryRepository {

    // 기간별 적응형 버킷. 클라이언트는 코드만 보내고, interval 문자열은 여기서만 만든다(임의 주입 차단).
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

    /** 추이 조회 대상 목록: 실제로 적재된 이벤트가 있는 리더보드만. */
    public List<UUID> leaderboardsWithEvents() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT leaderboard_id FROM audit_events ORDER BY leaderboard_id", UUID.class);
    }

    public List<TrendBucket> trend(UUID leaderboardId, Instant from, Instant to, String bucketCode) {
        String interval = BUCKET_INTERVALS.get(bucketCode);
        if (interval == null) {
            throw new IllegalArgumentException("unsupported bucket: " + bucketCode);
        }
        // date_bin으로 event_time을 버킷에 정렬해 delta 합·건수를 낸다. 누적은 프론트가 러닝 합으로 그린다.
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
}

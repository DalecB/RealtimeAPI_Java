package com.jake.realtimeapi.usagestats.persistence;

import com.jake.realtimeapi.usagestats.domain.model.UsageStatsDelta;
import com.jake.realtimeapi.usagestats.domain.repository.UsageStatsRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class UsageStatsRepositoryAdapter implements UsageStatsRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO usage_stats (
                api_key_id,
                bucket_type,
                bucket_start,
                request_count,
                allowed_count,
                blocked_count,
                idempotency_hit_count,
                idempotency_miss_count,
                idempotency_conflict_count
            ) VALUES (
                :apiKeyId,
                :bucketType,
                :bucketStart,
                :requestCount,
                :allowedCount,
                :blockedCount,
                :idempotencyHitCount,
                :idempotencyMissCount,
                :idempotencyConflictCount
            )
            ON CONFLICT (api_key_id, bucket_type, bucket_start)
            DO UPDATE SET
                request_count = usage_stats.request_count + EXCLUDED.request_count,
                allowed_count = usage_stats.allowed_count + EXCLUDED.allowed_count,
                blocked_count = usage_stats.blocked_count + EXCLUDED.blocked_count,
                idempotency_hit_count = usage_stats.idempotency_hit_count + EXCLUDED.idempotency_hit_count,
                idempotency_miss_count = usage_stats.idempotency_miss_count + EXCLUDED.idempotency_miss_count,
                idempotency_conflict_count = usage_stats.idempotency_conflict_count + EXCLUDED.idempotency_conflict_count,
                updated_at = now()
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UsageStatsRepositoryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void increment(UsageStatsDelta delta) {
        jdbcTemplate.update(UPSERT_SQL, new MapSqlParameterSource()
                .addValue("apiKeyId", delta.apiKeyId())
                .addValue("bucketType", delta.bucketType().name())
                .addValue("bucketStart", Timestamp.from(delta.bucketStart()))
                .addValue("requestCount", delta.requestCount())
                .addValue("allowedCount", delta.allowedCount())
                .addValue("blockedCount", delta.blockedCount())
                .addValue("idempotencyHitCount", delta.idempotencyHitCount())
                .addValue("idempotencyMissCount", delta.idempotencyMissCount())
                .addValue("idempotencyConflictCount", delta.idempotencyConflictCount()));
    }
}

package com.jake.realtimeapi.usagestats.application;

import com.jake.realtimeapi.usagestats.domain.model.UsageBucketType;
import com.jake.realtimeapi.usagestats.domain.model.UsageStatsDelta;
import com.jake.realtimeapi.usagestats.domain.repository.UsageStatsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class UsageStatsRecorder {

    private static final List<UsageBucketType> BUCKET_TYPES = List.of(UsageBucketType.HOUR, UsageBucketType.DAY);
    private static final Logger log = LoggerFactory.getLogger(UsageStatsRecorder.class);

    private final UsageStatsRepository usageStatsRepository;
    private final AtomicReference<ConcurrentHashMap<UsageStatsKey, UsageStatsDelta>> pendingDeltas =
            new AtomicReference<>(new ConcurrentHashMap<>());

    public UsageStatsRecorder(UsageStatsRepository usageStatsRepository) {
        this.usageStatsRepository = usageStatsRepository;
    }

    public void recordBlocked(long apiKeyId, Instant occurredAt) {
        record(apiKeyId, occurredAt, 1, 0, 1, 0, 0, 0);
    }

    public void recordProcessed(long apiKeyId, boolean replayed, Instant occurredAt) {
        record(apiKeyId, occurredAt, 1, 1, 0, replayed ? 1 : 0, replayed ? 0 : 1, 0);
    }

    public void recordIdempotencyConflict(long apiKeyId, Instant occurredAt) {
        // Conflict requests passed auth/rate-limit and reached the Redis hot path,
        // but did not mutate state because the payload hash mismatched an existing idempotency key.
        record(apiKeyId, occurredAt, 1, 1, 0, 0, 0, 1);
    }

    private void record(
            long apiKeyId,
            Instant occurredAt,
            long requestCount,
            long allowedCount,
            long blockedCount,
            long idempotencyHitCount,
            long idempotencyMissCount,
            long idempotencyConflictCount
    ) {
        for (UsageBucketType bucketType : BUCKET_TYPES) {
            UsageStatsDelta delta = new UsageStatsDelta(
                    apiKeyId,
                    bucketType,
                    bucketStart(occurredAt, bucketType),
                    requestCount,
                    allowedCount,
                    blockedCount,
                    idempotencyHitCount,
                    idempotencyMissCount,
                    idempotencyConflictCount
            );
            pendingDeltas.get().merge(keyOf(delta), delta, UsageStatsRecorder::merge);
        }
    }

    @Scheduled(
            fixedDelayString = "${app.usage-stats.flush-delay-ms:1000}",
            initialDelayString = "${app.usage-stats.flush-initial-delay-ms:1000}"
    )
    public void flushPending() {
        Map<UsageStatsKey, UsageStatsDelta> snapshot = pendingDeltas.getAndSet(new ConcurrentHashMap<>());
        if (snapshot.isEmpty()) {
            return;
        }

        for (Map.Entry<UsageStatsKey, UsageStatsDelta> entry : snapshot.entrySet()) {
            try {
                usageStatsRepository.increment(entry.getValue());
            } catch (RuntimeException exception) {
                log.warn(
                        "usage-stats.flush failed apiKeyId={} bucketType={} bucketStart={}",
                        entry.getKey().apiKeyId(),
                        entry.getKey().bucketType(),
                        entry.getKey().bucketStart(),
                        exception
                );
                pendingDeltas.get().merge(entry.getKey(), entry.getValue(), UsageStatsRecorder::merge);
            }
        }
    }

    @PreDestroy
    public void flushOnShutdown() {
        flushPending();
    }

    private Instant bucketStart(Instant occurredAt, UsageBucketType bucketType) {
        return switch (bucketType) {
            case HOUR -> occurredAt.truncatedTo(ChronoUnit.HOURS);
            case DAY -> ZonedDateTime.ofInstant(occurredAt, ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.DAYS)
                    .toInstant();
        };
    }

    private static UsageStatsKey keyOf(UsageStatsDelta delta) {
        return new UsageStatsKey(delta.apiKeyId(), delta.bucketType(), delta.bucketStart());
    }

    private static UsageStatsDelta merge(UsageStatsDelta left, UsageStatsDelta right) {
        return new UsageStatsDelta(
                left.apiKeyId(),
                left.bucketType(),
                left.bucketStart(),
                left.requestCount() + right.requestCount(),
                left.allowedCount() + right.allowedCount(),
                left.blockedCount() + right.blockedCount(),
                left.idempotencyHitCount() + right.idempotencyHitCount(),
                left.idempotencyMissCount() + right.idempotencyMissCount(),
                left.idempotencyConflictCount() + right.idempotencyConflictCount()
        );
    }

    private record UsageStatsKey(
            long apiKeyId,
            UsageBucketType bucketType,
            Instant bucketStart
    ) {
    }
}

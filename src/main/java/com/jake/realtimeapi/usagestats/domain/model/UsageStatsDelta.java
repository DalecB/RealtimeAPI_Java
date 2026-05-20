package com.jake.realtimeapi.usagestats.domain.model;

import java.time.Instant;

public record UsageStatsDelta(
        long apiKeyId,
        UsageBucketType bucketType,
        Instant bucketStart,
        long requestCount,
        long allowedCount,
        long blockedCount,
        long idempotencyHitCount,
        long idempotencyMissCount,
        long idempotencyConflictCount
) {
}

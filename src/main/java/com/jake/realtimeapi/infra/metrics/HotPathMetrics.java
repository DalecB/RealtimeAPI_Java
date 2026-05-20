package com.jake.realtimeapi.infra.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HotPathMetrics {

    private final Counter idempotencyHitCounter;
    private final Counter idempotencyMissCounter;
    private final Counter idempotencyConflictCounter;
    private final DistributionSummary processEventLuaDuration;
    private final DistributionSummary apiKeyLimitLuaDuration;
    private final Map<String, Counter> rateLimitBlockCounters = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public HotPathMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.idempotencyHitCounter = meterRegistry.counter("idempotency_hit_total");
        this.idempotencyMissCounter = meterRegistry.counter("idempotency_miss_total");
        this.idempotencyConflictCounter = meterRegistry.counter("idempotency_conflict_total");
        this.processEventLuaDuration = DistributionSummary.builder("redis_lua_duration_ms")
                .baseUnit("milliseconds")
                .tag("script", "process_event")
                .register(meterRegistry);
        this.apiKeyLimitLuaDuration = DistributionSummary.builder("redis_lua_duration_ms")
                .baseUnit("milliseconds")
                .tag("script", "check_api_key_limits")
                .register(meterRegistry);
    }

    public void recordIdempotencyHit() {
        idempotencyHitCounter.increment();
    }

    public void recordIdempotencyMiss() {
        idempotencyMissCounter.increment();
    }

    public void recordIdempotencyConflict() {
        idempotencyConflictCounter.increment();
    }

    public void recordProcessEventLuaDuration(double durationMs) {
        processEventLuaDuration.record(durationMs);
    }

    public void recordApiKeyLimitLuaDuration(double durationMs) {
        apiKeyLimitLuaDuration.record(durationMs);
    }

    public void recordRateLimitBlocked(long apiKeyId) {
        String cacheKey = Long.toString(apiKeyId);
        // PRD는 apiKeyId label을 요구하므로 key별 counter를 lazy하게 생성한다.
        rateLimitBlockCounters.computeIfAbsent(cacheKey, ignored -> Counter.builder("rate_limit_block_total")
                .tag("apiKeyId", cacheKey)
                .register(meterRegistry)).increment();
    }
}

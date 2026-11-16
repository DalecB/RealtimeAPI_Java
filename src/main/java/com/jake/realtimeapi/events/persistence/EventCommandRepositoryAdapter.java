package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.events.domain.exception.IdempotencyKeyReuseMismatchException;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.model.ProcessEventResult;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.events.persistence.redis.ProcessEventLuaExecutor;
import com.jake.realtimeapi.infra.circuitbreaker.RankingRebuildingException;
import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import com.jake.realtimeapi.infra.metrics.HotPathMetrics;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

@Repository
public class EventCommandRepositoryAdapter implements EventCommandRepository {

    private static final long IDEMPOTENCY_TTL_SECONDS = 24 * 60 * 60;
    private static final String IDEMPOTENCY_RECORD_PREFIX = "v2";
    private static final String IDEMPOTENCY_RECORD_SEPARATOR = ":";

    private final ProcessEventLuaExecutor processEventLuaExecutor;
    private final RedisHotPathCircuitBreaker redisHotPathCircuitBreaker;
    private final HotPathMetrics hotPathMetrics;
    private final long rebuildRetryAfterSeconds;

    public EventCommandRepositoryAdapter(
            ProcessEventLuaExecutor processEventLuaExecutor,
            RedisHotPathCircuitBreaker redisHotPathCircuitBreaker,
            HotPathMetrics hotPathMetrics,
            // 스냅샷 워커 주기. 다음 주기에 복구가 끝나므로 그만큼 뒤에 재시도하게 한다.
            @Value("${snapshots.worker.delay-ms:30000}") long snapshotWorkerDelayMs
    ) {
        this.processEventLuaExecutor = processEventLuaExecutor;
        this.redisHotPathCircuitBreaker = redisHotPathCircuitBreaker;
        this.hotPathMetrics = hotPathMetrics;
        this.rebuildRetryAfterSeconds = Math.max(1, snapshotWorkerDelayMs / 1000);
    }

    @Override
    public ProcessEventResult process(EventPayload payload, long apiKeyId) {
        String incomingPayloadHash = payloadHash(payload.payloadHashSource());
        Instant processedAt = Instant.now();
        String idempotencyValue = idempotencyValue(incomingPayloadHash, processedAt);

        // 멱등 충돌은 Redis 장애가 아니므로 브레이커 실패로 세지 않게 Lua 호출만 감싼다.
        ProcessEventLuaExecutor.LuaExecutionResult luaResult = redisHotPathCircuitBreaker.execute(() ->
                processEventLuaExecutor.execute(
                        payload.leaderboardId(),
                        payload.idempotencyKey(),
                        UserIdCodec.format(payload.userId()),
                        payload.deltaScore(),
                        IDEMPOTENCY_TTL_SECONDS,
                        idempotencyValue,
                        apiKeyId
                )
        );

        if (luaResult.cold()) {
            // Redis가 랭킹을 잃었다. 스냅샷 워커가 복구할 때까지 쓰기를 받지 않는다.
            throw new RankingRebuildingException(rebuildRetryAfterSeconds);
        }

        if (luaResult.isNew()) {
            hotPathMetrics.recordIdempotencyMiss();
            return new ProcessEventResult(payload.idempotencyKey(), false, processedAt);
        }

        StoredIdempotencyRecord storedRecord = parseStoredIdempotencyRecord(luaResult.value(), processedAt);
        if (!incomingPayloadHash.equals(storedRecord.payloadHash())) {
            hotPathMetrics.recordIdempotencyConflict();
            throw new IdempotencyKeyReuseMismatchException(payload.idempotencyKey());
        }

        hotPathMetrics.recordIdempotencyHit();
        return new ProcessEventResult(payload.idempotencyKey(), true, storedRecord.processedAt());
    }

    private String payloadHash(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    private String idempotencyValue(String payloadHash, Instant processedAt) {
        return IDEMPOTENCY_RECORD_PREFIX
                + IDEMPOTENCY_RECORD_SEPARATOR
                + payloadHash
                + IDEMPOTENCY_RECORD_SEPARATOR
                + processedAt.toEpochMilli();
    }

    private StoredIdempotencyRecord parseStoredIdempotencyRecord(String storedValue, Instant legacyProcessedAtFallback) {
        String safeStoredValue = Objects.requireNonNull(storedValue, "storedValue is required");
        String prefix = IDEMPOTENCY_RECORD_PREFIX + IDEMPOTENCY_RECORD_SEPARATOR;
        if (!safeStoredValue.startsWith(prefix)) {
            return new StoredIdempotencyRecord(safeStoredValue, legacyProcessedAtFallback);
        }

        int payloadHashEnd = safeStoredValue.indexOf(IDEMPOTENCY_RECORD_SEPARATOR, prefix.length());
        if (payloadHashEnd < 0) {
            throw new IllegalStateException("stored idempotency record is malformed: missing processedAt");
        }

        String storedPayloadHash = safeStoredValue.substring(prefix.length(), payloadHashEnd);
        String storedProcessedAtEpochMillis = safeStoredValue.substring(payloadHashEnd + 1);
        if (storedPayloadHash.isBlank() || storedProcessedAtEpochMillis.isBlank()) {
            throw new IllegalStateException("stored idempotency record is malformed: empty field");
        }

        try {
            long epochMillis = Long.parseLong(storedProcessedAtEpochMillis);
            return new StoredIdempotencyRecord(storedPayloadHash, Instant.ofEpochMilli(epochMillis));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("stored idempotency record is malformed: invalid processedAt", exception);
        }
    }

    private record StoredIdempotencyRecord(
            String payloadHash,
            Instant processedAt
    ) {
    }
}

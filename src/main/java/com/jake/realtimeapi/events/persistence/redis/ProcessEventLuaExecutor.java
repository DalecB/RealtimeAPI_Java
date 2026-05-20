package com.jake.realtimeapi.events.persistence.redis;

import com.jake.realtimeapi.infra.metrics.HotPathMetrics;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessEventLuaExecutor {

    private static final String SCRIPT_PATH = "lua/process_event.lua";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> processEventScript;
    private final HotPathMetrics hotPathMetrics;

    public ProcessEventLuaExecutor(StringRedisTemplate redisTemplate, HotPathMetrics hotPathMetrics) {
        this.redisTemplate = redisTemplate;
        this.hotPathMetrics = hotPathMetrics;

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(SCRIPT_PATH));
        script.setResultType(List.class);
        this.processEventScript = script;
    }

    public LuaExecutionResult execute(
            UUID leaderboardId,
            UUID eventUuid,
            String userId,
            long deltaScore,
            long ttlSeconds,
            String idempotencyValue
    ) {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
        if (eventUuid == null) {
            throw new IllegalArgumentException("eventUuid is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (deltaScore <= 0) {
            throw new IllegalArgumentException("deltaScore must be positive");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be positive");
        }
        if (idempotencyValue == null || idempotencyValue.isBlank()) {
            throw new IllegalArgumentException("idempotencyValue is required");
        }

        List<String> keys = List.of(
                EventRedisKeyFactory.idempotencyKey(leaderboardId, eventUuid),
                EventRedisKeyFactory.rankingKey(leaderboardId),
                EventRedisKeyFactory.auditStreamKey(leaderboardId)
        );

        long startedAt = System.nanoTime();
        List<?> rawResult = redisTemplate.execute(
                processEventScript,
                keys,
                userId,
                Long.toString(deltaScore),
                Long.toString(ttlSeconds),
                idempotencyValue
        );
        hotPathMetrics.recordProcessEventLuaDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));

        if (rawResult == null || rawResult.size() != 2) {
            throw new IllegalStateException("process_event.lua returned unexpected result");
        }

        long isNew = toLong(rawResult.get(0), "isNew");
        if (isNew != 0L && isNew != 1L) {
            throw new IllegalStateException("process_event.lua returned invalid isNew flag: " + isNew);
        }

        String value = Objects.toString(rawResult.get(1), null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("process_event.lua returned empty payload value");
        }

        return new LuaExecutionResult(isNew == 1L, value);
    }

    private long toLong(Object value, String fieldName) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("process_event.lua returned non-numeric " + fieldName + ": " + text, e);
            }
        }
        throw new IllegalStateException("process_event.lua returned unsupported type for " + fieldName + ": " + value);
    }

    public record LuaExecutionResult(
            boolean isNew,
            String value
    ) {
    }
}

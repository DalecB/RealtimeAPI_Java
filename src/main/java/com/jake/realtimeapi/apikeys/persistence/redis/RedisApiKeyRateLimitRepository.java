package com.jake.realtimeapi.apikeys.persistence.redis;

import com.jake.realtimeapi.apikeys.domain.model.RateLimitCheckResult;
import com.jake.realtimeapi.apikeys.domain.repository.ApiKeyRateLimitRepository;
import com.jake.realtimeapi.infra.metrics.HotPathMetrics;
import com.jake.realtimeapi.support.redis.ApiKeyRedisKeyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Repository
public class RedisApiKeyRateLimitRepository implements ApiKeyRateLimitRepository {

    private static final int RATE_LIMIT_TTL_SECONDS = 2;
    private static final String SCRIPT_PATH = "lua/check_api_key_limits.lua";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> checkLimitsScript;
    private final HotPathMetrics hotPathMetrics;

    public RedisApiKeyRateLimitRepository(StringRedisTemplate redisTemplate, HotPathMetrics hotPathMetrics) {
        this.redisTemplate = redisTemplate;
        this.hotPathMetrics = hotPathMetrics;

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(SCRIPT_PATH));
        script.setResultType(List.class);
        this.checkLimitsScript = script;
    }

    @Override
    public RateLimitCheckResult checkAndConsume(long apiKeyId, int rateLimitPerSec, int dailyQuota, Instant now) {
        long secondBucketStart = now.getEpochSecond();
        long dayBucketStart = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long dailyQuotaTtl = secondsUntilNextUtcDay(now);

        List<String> keys = List.of(
                ApiKeyRedisKeyFactory.rateLimitKey(apiKeyId, secondBucketStart),
                ApiKeyRedisKeyFactory.dailyQuotaKey(apiKeyId, dayBucketStart)
        );

        long startedAt = System.nanoTime();
        List<?> rawResult = redisTemplate.execute(
                checkLimitsScript,
                keys,
                Integer.toString(rateLimitPerSec),
                Integer.toString(RATE_LIMIT_TTL_SECONDS),
                Integer.toString(dailyQuota),
                Long.toString(dailyQuotaTtl)
        );
        hotPathMetrics.recordApiKeyLimitLuaDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));

        if (rawResult.size() != 3) {
            throw new IllegalStateException("check_api_key_limits.lua returned unexpected result");
        }

        long decision = toLong(rawResult.get(0), "decision");
        long currentRateCount = toLong(rawResult.get(1), "currentRateCount");

        if (decision == 1L) {
            int remaining = Math.max(0, rateLimitPerSec - (int) currentRateCount);
            return RateLimitCheckResult.allowed(remaining);
        }
        if (decision == 0L) {
            return RateLimitCheckResult.rateLimited(1L);
        }
        if (decision == -1L) {
            return RateLimitCheckResult.quotaExceeded(dailyQuotaTtl);
        }
        throw new IllegalStateException("check_api_key_limits.lua returned unsupported decision: " + decision);
    }

    private long secondsUntilNextUtcDay(Instant now) {
        ZonedDateTime utcNow = now.atZone(ZoneOffset.UTC);
        ZonedDateTime nextDay = utcNow.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        return Math.max(1, nextDay.toEpochSecond() - utcNow.toEpochSecond());
    }

    private long toLong(Object value, String fieldName) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("check_api_key_limits.lua returned non-numeric " + fieldName + ": " + text, e);
            }
        }
        throw new IllegalStateException("check_api_key_limits.lua returned unsupported type for " + fieldName + ": " + value);
    }
}

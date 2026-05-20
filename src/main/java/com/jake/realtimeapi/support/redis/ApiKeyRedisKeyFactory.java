package com.jake.realtimeapi.support.redis;

public final class ApiKeyRedisKeyFactory {

    private ApiKeyRedisKeyFactory() {
    }

    public static String rateLimitKey(long apiKeyId, long windowStartEpochSecond) {
        return "rl:{" + apiKeyId + "}:" + windowStartEpochSecond;
    }

    public static String dailyQuotaKey(long apiKeyId, long dayStartEpochSecond) {
        return "qt:{" + apiKeyId + "}:" + dayStartEpochSecond;
    }
}

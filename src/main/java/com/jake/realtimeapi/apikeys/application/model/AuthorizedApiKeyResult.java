package com.jake.realtimeapi.apikeys.application.model;

public record AuthorizedApiKeyResult(
        long apiKeyId,
        int rateLimitRemaining
) {
}

package com.jake.realtimeapi.apikeys.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class ApiKeySecretFactory {

    private static final int KEY_BYTES = 24;
    private static final String RAW_KEY_PREFIX = "rk_";
    private static final int KEY_PREFIX_LENGTH = 15;

    private final SecureRandom secureRandom = new SecureRandom();

    public GeneratedApiKey generate() {
        byte[] randomBytes = new byte[KEY_BYTES];
        secureRandom.nextBytes(randomBytes);
        String rawKey = RAW_KEY_PREFIX + HexFormat.of().formatHex(randomBytes);
        return new GeneratedApiKey(rawKey, rawKey.substring(0, Math.min(KEY_PREFIX_LENGTH, rawKey.length())));
    }

    public String hash(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException("rawKey is required");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    public record GeneratedApiKey(
            String rawKey,
            String keyPrefix
    ) {
    }
}

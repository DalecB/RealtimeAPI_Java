package com.jake.realtimeapi.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.auth.domain.exception.AdminAuthenticationException;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwtTokenCodec {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final byte[] secretBytes;
    private final long tokenTtlSeconds;

    public JwtTokenCodec(
            ObjectMapper objectMapper,
            @Value("${app.auth.jwt.secret:${APP_AUTH_JWT_SECRET:local-dev-admin-jwt-secret-change-me}}") String secret,
            @Value("${app.auth.jwt.ttl-seconds:3600}") long tokenTtlSeconds
    ) {
        this.objectMapper = objectMapper;
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public IssuedToken issue(AuthenticatedAdminUser user) {
        try {
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(tokenTtlSeconds);

            String header = encodeJson(Map.of(
                    "alg", "HS256",
                    "typ", "JWT"
            ));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", Long.toString(user.userId()));
            payload.put("userId", user.userId());
            payload.put("externalId", user.externalId());
            payload.put("iat", now.getEpochSecond());
            payload.put("exp", expiresAt.getEpochSecond());

            String payloadPart = encodeJson(payload);
            String signingInput = header + "." + payloadPart;
            String signature = sign(signingInput);
            return new IssuedToken(signingInput + "." + signature, expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("failed to issue jwt token", e);
        }
    }

    public AuthenticatedAdminUser parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new AdminAuthenticationException("admin token is invalid");
            }

            String signingInput = parts[0] + "." + parts[1];
            String expectedSignature = sign(signingInput);
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                throw new AdminAuthenticationException("admin token is invalid");
            }

            JsonNode payload = objectMapper.readTree(URL_DECODER.decode(parts[1]));
            long exp = payload.path("exp").asLong(0L);
            if (exp == 0L || Instant.now().getEpochSecond() >= exp) {
                throw new AdminAuthenticationException("admin token has expired");
            }

            long userId = payload.path("userId").asLong(0L);
            String externalId = payload.path("externalId").asText(null);
            if (userId <= 0 || externalId == null || externalId.isBlank()) {
                throw new AdminAuthenticationException("admin token is invalid");
            }
            return new AuthenticatedAdminUser(userId, externalId);
        } catch (AdminAuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AdminAuthenticationException("admin token is invalid");
        }
    }

    private String encodeJson(Map<String, ?> value) throws Exception {
        return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private String sign(String input) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
        return URL_ENCODER.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length; i++) {
            diff |= left[i] ^ right[i];
        }
        return diff == 0;
    }

    public record IssuedToken(String accessToken, Instant expiresAt) {
    }
}

package com.jake.realtimeapi.users.api.dto;

import java.time.Instant;

/**
 * 단건 유저 응답 DTO.
 *
 * @param id 내부 PK
 * @param externalId 외부 식별자
 * @param createdAt 생성 시각(UTC 기준 Instant)
 */
public record UserResponse(
        Long id,
        String externalId,
        Instant createdAt
) {
}

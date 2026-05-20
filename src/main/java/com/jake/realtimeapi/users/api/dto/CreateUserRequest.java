package com.jake.realtimeapi.users.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "유저 생성" API 요청 바디 DTO.
 *
 * <p>Controller 진입 시 JSON body가 이 record로 매핑됩니다.
 * Bean Validation 어노테이션으로 1차 입력 검증을 수행합니다.
 *
 * @param externalId 클라이언트가 전달하는 유저 외부 식별자
 */
public record CreateUserRequest(
        @NotBlank
        @Size(max = 30)
        String externalId
) {
}

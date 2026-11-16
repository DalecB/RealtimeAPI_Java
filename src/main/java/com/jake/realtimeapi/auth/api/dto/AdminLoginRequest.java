package com.jake.realtimeapi.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * password는 비어 있어도 검증 단계에서 막지 않는다. 누락도 틀린 비밀번호와 같은 401로 응답한다.
 */
public record AdminLoginRequest(
        @NotBlank
        @Size(max = 30)
        String externalId,
        String password
) {
}

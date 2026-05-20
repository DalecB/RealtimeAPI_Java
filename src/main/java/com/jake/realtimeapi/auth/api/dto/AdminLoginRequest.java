package com.jake.realtimeapi.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminLoginRequest(
        @NotBlank
        @Size(max = 30)
        String externalId
) {
}

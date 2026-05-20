package com.jake.realtimeapi.leaderboards.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateLeaderboardRequest(
        @NotNull
        UUID projectId,
        @NotBlank
        @Size(max = 255)
        String name
) {
}

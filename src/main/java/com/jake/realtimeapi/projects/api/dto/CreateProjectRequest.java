package com.jake.realtimeapi.projects.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank
        @Size(max = 100)
        String name
) {
}

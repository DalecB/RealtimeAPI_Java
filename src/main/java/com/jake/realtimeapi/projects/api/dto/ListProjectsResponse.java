package com.jake.realtimeapi.projects.api.dto;

import java.util.List;

public record ListProjectsResponse(
        List<ProjectResponse> items,
        int offset,
        int limit,
        int returnedCount
) {
}

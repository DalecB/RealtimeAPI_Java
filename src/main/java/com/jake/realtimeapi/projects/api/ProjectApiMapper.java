package com.jake.realtimeapi.projects.api;

import com.jake.realtimeapi.projects.api.dto.CreatedProjectResponse;
import com.jake.realtimeapi.projects.api.dto.ListProjectsResponse;
import com.jake.realtimeapi.projects.api.dto.ProjectResponse;
import com.jake.realtimeapi.projects.application.model.CreatedProjectWithApiKeyResult;
import com.jake.realtimeapi.projects.application.usecase.ProjectSlice;
import com.jake.realtimeapi.projects.domain.model.Project;

import java.util.List;

public final class ProjectApiMapper {

    private ProjectApiMapper() {}

    public static CreatedProjectResponse toCreatedResponse(CreatedProjectWithApiKeyResult result) {
        return new CreatedProjectResponse(
                result.project().id(),
                result.project().adminId(),
                result.project().name(),
                result.project().createdAt(),
                new CreatedProjectResponse.DefaultApiKeyResponse(
                        result.defaultApiKey().id(),
                        result.defaultApiKey().rawKey(),
                        result.defaultApiKey().keyPrefix(),
                        result.defaultApiKey().status().name(),
                        result.defaultApiKey().rateLimitPerSec(),
                        result.defaultApiKey().dailyQuota(),
                        result.defaultApiKey().createdAt(),
                        result.defaultApiKey().expiresAt()
                )
        );
    }

    public static ProjectResponse toResponse(Project project) {
        return new ProjectResponse(project.id(), project.adminId(), project.name(), project.createdAt());
    }

    public static ListProjectsResponse toResponse(ProjectSlice slice) {
        List<ProjectResponse> items = slice.projects().stream()
                .map(ProjectApiMapper::toResponse)
                .toList();
        return new ListProjectsResponse(items, slice.offset(), slice.limit(), items.size());
    }

    public static ListProjectsResponse toResponse(List<Project> projects) {
        List<ProjectResponse> items = projects.stream()
                .map(ProjectApiMapper::toResponse)
                .toList();
        return new ListProjectsResponse(items, 0, items.size(), items.size());
    }
}

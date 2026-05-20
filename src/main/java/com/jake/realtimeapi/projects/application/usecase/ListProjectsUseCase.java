package com.jake.realtimeapi.projects.application.usecase;

import com.jake.realtimeapi.projects.domain.model.Project;

import java.util.List;

public interface ListProjectsUseCase {

    ProjectSlice list(int offset, int limit);

    List<Project> getByAdminId(Long id);
}

package com.jake.realtimeapi.projects.application.usecase;

import com.jake.realtimeapi.projects.domain.model.Project;

import java.util.List;

public record ProjectSlice(
        List<Project> projects, int offset, int limit
) {
}

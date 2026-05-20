package com.jake.realtimeapi.projects.application.usecase;

import com.jake.realtimeapi.projects.domain.model.Project;

import java.util.UUID;

public interface GetProjectUseCase {

    Project getById(UUID id);

    Project getByName(String name);
}

package com.jake.realtimeapi.projects.application.usecase;

import com.jake.realtimeapi.projects.application.command.CreateProjectCommand;
import com.jake.realtimeapi.projects.domain.model.Project;

public interface CreateProjectUseCase {

    Project create(CreateProjectCommand command);
}

package com.jake.realtimeapi.projects.application.usecase;

import com.jake.realtimeapi.projects.application.command.CreateProjectCommand;
import com.jake.realtimeapi.projects.application.model.CreatedProjectWithApiKeyResult;

public interface CreateProjectWithDefaultApiKeyUseCase {

    CreatedProjectWithApiKeyResult create(CreateProjectCommand command);
}

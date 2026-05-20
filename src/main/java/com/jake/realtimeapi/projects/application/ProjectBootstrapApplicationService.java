package com.jake.realtimeapi.projects.application;

import com.jake.realtimeapi.apikeys.application.command.CreateApiKeyCommand;
import com.jake.realtimeapi.apikeys.application.model.IssuedApiKeyResult;
import com.jake.realtimeapi.apikeys.application.usecase.CreateApiKeyUseCase;
import com.jake.realtimeapi.projects.application.command.CreateProjectCommand;
import com.jake.realtimeapi.projects.application.model.CreatedProjectWithApiKeyResult;
import com.jake.realtimeapi.projects.application.usecase.CreateProjectUseCase;
import com.jake.realtimeapi.projects.application.usecase.CreateProjectWithDefaultApiKeyUseCase;
import com.jake.realtimeapi.projects.domain.model.Project;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectBootstrapApplicationService implements CreateProjectWithDefaultApiKeyUseCase {

    private final CreateProjectUseCase createProjectUseCase;
    private final CreateApiKeyUseCase createApiKeyUseCase;

    public ProjectBootstrapApplicationService(
            CreateProjectUseCase createProjectUseCase,
            CreateApiKeyUseCase createApiKeyUseCase
    ) {
        this.createProjectUseCase = createProjectUseCase;
        this.createApiKeyUseCase = createApiKeyUseCase;
    }

    @Override
    public CreatedProjectWithApiKeyResult create(CreateProjectCommand command) {
        // 프로젝트 생성 직후 바로 사용할 수 있도록 기본 API key 1개를 함께 부트스트랩한다.
        Project project = createProjectUseCase.create(command);
        IssuedApiKeyResult defaultApiKey = createApiKeyUseCase.create(
                new CreateApiKeyCommand(project.id(), project.adminId(), null, null, null)
        );
        return new CreatedProjectWithApiKeyResult(project, defaultApiKey);
    }
}

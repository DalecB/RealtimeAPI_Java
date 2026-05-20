package com.jake.realtimeapi.projects.application;

import com.jake.realtimeapi.apikeys.application.command.CreateApiKeyCommand;
import com.jake.realtimeapi.apikeys.application.model.IssuedApiKeyResult;
import com.jake.realtimeapi.apikeys.application.usecase.CreateApiKeyUseCase;
import com.jake.realtimeapi.apikeys.domain.model.ApiKeyStatus;
import com.jake.realtimeapi.projects.application.command.CreateProjectCommand;
import com.jake.realtimeapi.projects.application.model.CreatedProjectWithApiKeyResult;
import com.jake.realtimeapi.projects.application.usecase.CreateProjectUseCase;
import com.jake.realtimeapi.projects.domain.model.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectBootstrapApplicationServiceTest {

    @Mock
    private CreateProjectUseCase createProjectUseCase;

    @Mock
    private CreateApiKeyUseCase createApiKeyUseCase;

    @InjectMocks
    private ProjectBootstrapApplicationService projectBootstrapApplicationService;

    @Test
    void create_createsProjectAndDefaultApiKey() {
        CreateProjectCommand command = new CreateProjectCommand("project-1", 1L);
        UUID projectId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-03-19T00:00:00Z");

        Project project = new Project(projectId, 1L, "project-1", createdAt);
        IssuedApiKeyResult apiKey = new IssuedApiKeyResult(
                10L,
                projectId,
                "rk_test_secret",
                "rk_test_secret",
                ApiKeyStatus.ACTIVE,
                100,
                1_000_000,
                createdAt,
                null
        );

        when(createProjectUseCase.create(command)).thenReturn(project);
        when(createApiKeyUseCase.create(new CreateApiKeyCommand(projectId, 1L, null, null, null))).thenReturn(apiKey);

        CreatedProjectWithApiKeyResult result = projectBootstrapApplicationService.create(command);

        assertEquals(project, result.project());
        assertEquals(apiKey, result.defaultApiKey());
        verify(createProjectUseCase).create(command);
        verify(createApiKeyUseCase).create(new CreateApiKeyCommand(projectId, 1L, null, null, null));
    }
}

package com.jake.realtimeapi.projects.api;

import com.jake.realtimeapi.auth.api.AdminWebMvcConfig;
import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import com.jake.realtimeapi.projects.application.model.CreatedProjectWithApiKeyResult;
import com.jake.realtimeapi.projects.application.command.CreateProjectCommand;
import com.jake.realtimeapi.projects.application.usecase.CreateProjectWithDefaultApiKeyUseCase;
import com.jake.realtimeapi.projects.application.usecase.GetProjectUseCase;
import com.jake.realtimeapi.projects.application.usecase.ListProjectsUseCase;
import com.jake.realtimeapi.projects.application.usecase.ProjectSlice;
import com.jake.realtimeapi.apikeys.application.model.IssuedApiKeyResult;
import com.jake.realtimeapi.apikeys.domain.model.ApiKeyStatus;
import com.jake.realtimeapi.projects.domain.exception.ProjectNotFoundException;
import com.jake.realtimeapi.projects.domain.model.Project;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProjectController.class)
@Import({GlobalExceptionHandler.class, AdminWebMvcConfig.class})
public class ProjectControllerTest {

    private static final String ADMIN_AUTHORIZATION = "Bearer admin-jwt";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateProjectWithDefaultApiKeyUseCase createProjectWithDefaultApiKeyUseCase;

    @MockitoBean
    private GetProjectUseCase getProjectUseCase;

    @MockitoBean
    private ListProjectsUseCase listProjectsUseCase;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void create_returns201AndProjectWithDefaultApiKey() throws Exception {
        UUID projectId = UUID.randomUUID();
        Long adminId = 1L;
        String name = "project-1";
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");
        IssuedApiKeyResult defaultApiKey = new IssuedApiKeyResult(
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

        when(createProjectWithDefaultApiKeyUseCase.create(any(CreateProjectCommand.class)))
                .thenReturn(new CreatedProjectWithApiKeyResult(
                        new Project(projectId, adminId, name, createdAt),
                        defaultApiKey
                ));
        when(authenticateAdminJwtUseCase.authenticate(ADMIN_AUTHORIZATION))
                .thenReturn(new AuthenticatedAdminUser(adminId, "admin-user"));

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);

        mockMvc.perform(post("/projects")
                        .header("Authorization", ADMIN_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(projectId.toString()))
                .andExpect(jsonPath("$.adminId").value(adminId.toString()))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.createdAt").value(createdAt.toString()))
                .andExpect(jsonPath("$.defaultApiKey.id").value(10))
                .andExpect(jsonPath("$.defaultApiKey.rawKey").value("rk_test_secret"))
                .andExpect(jsonPath("$.defaultApiKey.status").value("ACTIVE"))
                .andExpect(jsonPath("$.defaultApiKey.rateLimitPerSec").value(100))
                .andExpect(jsonPath("$.defaultApiKey.dailyQuota").value(1000000));

        ArgumentCaptor<CreateProjectCommand> captor = ArgumentCaptor.forClass(CreateProjectCommand.class);
        verify(createProjectWithDefaultApiKeyUseCase).create(captor.capture());
        CreateProjectCommand captured = captor.getValue();
        assertEquals(name, captured.name());
        assertEquals(adminId, captured.adminId());
    }

    @Test
    void create_nameEmpty_returns400() throws Exception {
        String name = "";

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        when(authenticateAdminJwtUseCase.authenticate(ADMIN_AUTHORIZATION))
                .thenReturn(new AuthenticatedAdminUser(1L, "admin-user"));

        mockMvc.perform(post("/projects")
                    .header("Authorization", ADMIN_AUTHORIZATION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(createProjectWithDefaultApiKeyUseCase, never()).create(any());
    }

    @Test
    void create_nameTooLong_returns400() throws Exception {
        String name = "1".repeat(101);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        when(authenticateAdminJwtUseCase.authenticate(ADMIN_AUTHORIZATION))
                .thenReturn(new AuthenticatedAdminUser(1L, "admin-user"));

        mockMvc.perform(post("/projects")
                        .header("Authorization", ADMIN_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(createProjectWithDefaultApiKeyUseCase, never()).create(any());
    }

    @Test
    void create_nameMissing_returns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        when(authenticateAdminJwtUseCase.authenticate(ADMIN_AUTHORIZATION))
                .thenReturn(new AuthenticatedAdminUser(1L, "admin-user"));

        mockMvc.perform(post("/projects")
                        .header("Authorization", ADMIN_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(createProjectWithDefaultApiKeyUseCase, never()).create(any());
    }

    @Test
    void create_withoutAuthorization_returns401() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "project-1");

        mockMvc.perform(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ADMIN_AUTH"));

        verify(createProjectWithDefaultApiKeyUseCase, never()).create(any());
    }

    @Test
    void getById_idExists_returnsProject() throws Exception {
        UUID projectId = UUID.randomUUID();
        String name = "project-1";
        Long adminId = 1L;
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");

        Project expected = new Project(projectId, adminId, name, createdAt);

        when(getProjectUseCase.getById(projectId)).thenReturn(expected);

        mockMvc.perform(get("/projects/{projectId}",projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.adminId").value(adminId.toString()))
                .andExpect(jsonPath("$.createdAt").value(createdAt.toString()));

        verify(getProjectUseCase).getById(projectId);
    }

    @Test
    void getById_idNotExists_returns404() throws Exception {
        UUID projectId = UUID.randomUUID();

        when(getProjectUseCase.getById(projectId))
                .thenThrow(new ProjectNotFoundException(projectId));

        mockMvc.perform(get("/projects/{projectId}", projectId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        verify(getProjectUseCase).getById(projectId);
    }

    @Test
    void get_whenNameProvided_returnsProjectByName() throws Exception {
        UUID projectId = UUID.randomUUID();
        String name = "project-1";
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");

        Project expected = new Project(projectId, 1L, name, createdAt);

        when(getProjectUseCase.getByName(name)).thenReturn(expected);

        mockMvc.perform(get("/projects")
                        .param("name", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(projectId.toString()))
                .andExpect(jsonPath("$.items[0].name").value(name))
                .andExpect(jsonPath("$.items[0].adminId").value("1"))
                .andExpect(jsonPath("$.items[0].createdAt").value(createdAt.toString()));

        verify(getProjectUseCase).getByName(name);
    }

    @Test
    void get_whenAdminIdProvided_returnsProjectsByAdminId() throws Exception {
        UUID projectId = UUID.randomUUID();
        Long adminId = 1L;
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");

        List<Project> expectedProjects = new ArrayList<>();
        expectedProjects.add(new Project(projectId, adminId, "project-1", createdAt));

        when(listProjectsUseCase.getByAdminId(adminId)).thenReturn(expectedProjects);

        mockMvc.perform(get("/projects")
                        .param("adminId", adminId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(projectId.toString()))
                .andExpect(jsonPath("$.items[0].name").value("project-1"))
                .andExpect(jsonPath("$.items[0].adminId").value(adminId.toString()))
                .andExpect(jsonPath("$.items[0].createdAt").value(createdAt.toString()));

        verify(listProjectsUseCase).getByAdminId(adminId);
    }

    @Test
    void get_whenOffsetAndLimitProvided_returnsProjectSlice() throws Exception {
        int offset = 0;
        int limit = 20;

        List<Project> expectedProjects = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID projectId = UUID.randomUUID();
            Long adminId = 1L;
            Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");
            Project expected = new Project(projectId, adminId, "project-" + i, createdAt);
            expectedProjects.add(expected);
        }

        ProjectSlice slice = new ProjectSlice(expectedProjects, offset, limit);

        when(listProjectsUseCase.list(offset, limit)).thenReturn(slice);

        mockMvc.perform(get("/projects")
                        .param("offset", String.valueOf(offset))
                        .param("limit", String.valueOf(limit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(expectedProjects.size()))
                .andExpect(jsonPath("$.items[0].name").value("project-0"))
                .andExpect(jsonPath("$.items[19].name").value("project-19"))
                .andExpect(jsonPath("$.offset").value(offset))
                .andExpect(jsonPath("$.limit").value(limit));

        verify(listProjectsUseCase).list(offset, limit);
    }

    @Test
    void get_whenNothingProvided_returnsEmptyList() throws Exception {
        when(listProjectsUseCase.list(0, 20))
                .thenReturn(new ProjectSlice(List.of(), 0, 20));

        mockMvc.perform(get("/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void get_whenNameAndAdminIdProvided_returns400() throws Exception {
        String name = "project-1";
        long adminId = 1L;

        mockMvc.perform(get("/projects")
                        .param("name", name)
                        .param("adminId", Long.toString(adminId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void get_whenNameAndLimitProvided_returns400() throws Exception {
        String name = "project-1";
        int limit = 20;

        mockMvc.perform(get("/projects")
                        .param("name", name)
                        .param("limit", String.valueOf(limit)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}

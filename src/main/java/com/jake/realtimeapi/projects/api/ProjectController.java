package com.jake.realtimeapi.projects.api;

import com.jake.realtimeapi.auth.api.CurrentAdminUser;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import com.jake.realtimeapi.projects.api.dto.CreateProjectRequest;
import com.jake.realtimeapi.projects.api.dto.CreatedProjectResponse;
import com.jake.realtimeapi.projects.api.dto.ListProjectsResponse;
import com.jake.realtimeapi.projects.api.dto.ProjectResponse;
import com.jake.realtimeapi.projects.application.command.CreateProjectCommand;
import com.jake.realtimeapi.projects.application.usecase.CreateProjectWithDefaultApiKeyUseCase;
import com.jake.realtimeapi.projects.application.usecase.GetProjectUseCase;
import com.jake.realtimeapi.projects.application.usecase.ListProjectsUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final CreateProjectWithDefaultApiKeyUseCase createProjectWithDefaultApiKeyUseCase;
    private final GetProjectUseCase getProjectUseCase;
    private final ListProjectsUseCase listProjectsUseCase;

    public ProjectController(
            CreateProjectWithDefaultApiKeyUseCase createProjectWithDefaultApiKeyUseCase,
            GetProjectUseCase getProjectUseCase,
            ListProjectsUseCase listProjectsUseCase
    ) {
        this.createProjectWithDefaultApiKeyUseCase = createProjectWithDefaultApiKeyUseCase;
        this.getProjectUseCase = getProjectUseCase;
        this.listProjectsUseCase = listProjectsUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedProjectResponse create(
            @CurrentAdminUser AuthenticatedAdminUser currentAdminUser,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        return ProjectApiMapper.toCreatedResponse(
                createProjectWithDefaultApiKeyUseCase.create(new CreateProjectCommand(request.name(), currentAdminUser.userId()))
        );
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getById(@PathVariable UUID projectId) {
        return ProjectApiMapper.toResponse(getProjectUseCase.getById(projectId));
    }

    @GetMapping
    public ListProjectsResponse get(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit
    ) {
        if (name != null && adminId != null) {
            throw new IllegalArgumentException("name and adminId cannot be used together");
        }

        if ((name != null || adminId != null) && (offset != null || limit != null)) {
            throw new IllegalArgumentException("offset and limit can only be used when neither name nor adminId is provided");
        }

        if (name != null) {
            return ProjectApiMapper.toResponse(List.of(getProjectUseCase.getByName(name)));
        }

        if (adminId != null) {
            return ProjectApiMapper.toResponse(listProjectsUseCase.getByAdminId(adminId));
        }

        int resolvedOffset = offset == null ? 0 : offset;
        int resolvedLimit = limit == null ? 20 : limit;
        return ProjectApiMapper.toResponse(listProjectsUseCase.list(resolvedOffset, resolvedLimit));
    }
}

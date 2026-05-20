package com.jake.realtimeapi.projects.application;

import com.jake.realtimeapi.projects.application.command.CreateProjectCommand;
import com.jake.realtimeapi.projects.application.usecase.CreateProjectUseCase;
import com.jake.realtimeapi.projects.application.usecase.GetProjectUseCase;
import com.jake.realtimeapi.projects.application.usecase.ListProjectsUseCase;
import com.jake.realtimeapi.projects.application.usecase.ProjectSlice;
import com.jake.realtimeapi.projects.domain.exception.ProjectAlreadyExistsException;
import com.jake.realtimeapi.projects.domain.exception.ProjectNotFoundException;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProjectApplicationService implements CreateProjectUseCase, GetProjectUseCase, ListProjectsUseCase {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ProjectRepository projectRepository;


    public ProjectApplicationService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Override
    @Transactional
    public Project create(CreateProjectCommand command) {
        if (projectRepository.existsByName(command.name())) {
            throw new ProjectAlreadyExistsException(command.name());
        }
        return projectRepository.save(Project.newProject(command.name(), command.adminId()));
    }

    @Override
    public Project getById(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException(id));
    }

    @Override
    public Project getByName(String name) {
        return projectRepository.findByName(name)
                .orElseThrow(() -> new ProjectNotFoundException(name));
    }

    @Override
    public ProjectSlice list(int offset, int limit) {
        int normalizedOffset = Math.max(0, offset);
        int normalizedLimit = limit <= 0 ? DEFAULT_LIMIT :Math.min(limit, MAX_LIMIT);
        return new ProjectSlice(projectRepository.findPage(normalizedOffset, normalizedLimit), normalizedOffset, normalizedLimit);
    }

    @Override
    public List<Project> getByAdminId(Long id) {
        return projectRepository.findByAdminId(id);
    }
}

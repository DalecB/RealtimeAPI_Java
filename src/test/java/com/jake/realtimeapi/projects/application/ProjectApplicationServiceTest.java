package com.jake.realtimeapi.projects.application;

import com.jake.realtimeapi.projects.application.command.CreateProjectCommand;
import com.jake.realtimeapi.projects.domain.exception.ProjectAlreadyExistsException;
import com.jake.realtimeapi.projects.domain.exception.ProjectNotFoundException;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ProjectApplicationServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private ProjectApplicationService service;

    @Test
    void create_whenNameDoesNotExist_savesNewProjectAndReturnsIt() {
        UUID projectId = UUID.randomUUID();
        String name = "Project-1";
        Long adminId = 1L;
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");

        CreateProjectCommand command = new CreateProjectCommand(name, adminId);
        Project expected = new Project(projectId, adminId, name, createdAt);

        when(projectRepository.existsByName(name)).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenReturn(expected);

        Project result = service.create(command);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        Project captured = captor.getValue();

        assertEquals(name, captured.name());
        assertNull(captured.id());
        assertEquals(adminId, captured.adminId());
        assertNull(captured.createdAt());

        verify(projectRepository).existsByName(name);

        assertEquals(projectId, result.id());
        assertEquals(name, result.name());
        assertEquals(adminId, result.adminId());
        assertEquals(createdAt, result.createdAt());
    }

    @Test
    void create_whenNameAlreadyExists_throwsProjectAlreadyExistsException() {
        String name = "Project-1";
        CreateProjectCommand command = new CreateProjectCommand(name, 1L);

        when(projectRepository.existsByName(name)).thenReturn(true);

        ProjectAlreadyExistsException exception = assertThrows(
                ProjectAlreadyExistsException.class,
                () -> service.create(command)
        );

        assertEquals("Project already exists: name=" + name, exception.getMessage());

        verify(projectRepository).existsByName(name);
        verify(projectRepository, never()).save(any(Project.class));
    }

    @Test
    void getById_whenIdExist_returnsProject() {
        UUID projectId = UUID.randomUUID();
        String name = "Project-1";
        Long adminId = 1L;
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");

        Project expected = new Project(projectId, 1L, "Project-1", Instant.parse("2026-03-12T00:00:00Z"));

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(expected));

        Project result = service.getById(projectId);

        assertEquals(projectId, result.id());
        assertEquals(name, result.name());
        assertEquals(adminId, result.adminId());
        assertEquals(createdAt, result.createdAt());
    }

    @Test
    void getById_whenIdDoesNotExist_throwsProjectNotFoundException() {
        UUID projectId = UUID.randomUUID();

        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        ProjectNotFoundException exception = assertThrows(
                ProjectNotFoundException.class,
                () -> service.getById(projectId)
        );

        assertEquals("Project not found: id=" + projectId, exception.getMessage());

        verify(projectRepository).findById(projectId);
    }

    @Test
    void getByName_whenNameExist_returnsProject() {
        UUID projectId = UUID.randomUUID();
        String name = "Project-1";
        Long adminId = 1L;
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");

        Project expected = new Project(projectId, 1L, "Project-1", createdAt);

        when(projectRepository.findByName(name)).thenReturn(Optional.of(expected));

        Project result = service.getByName(name);

        assertEquals(projectId, result.id());
        assertEquals(name, result.name());
        assertEquals(adminId, result.adminId());
        assertEquals(createdAt, result.createdAt());
    }

    @Test
    void getByName_whenNameDoesNotExist_throwsProjectNotFoundException() {
        String name = "Project-1";

        when(projectRepository.findByName(name)).thenReturn(Optional.empty());

        ProjectNotFoundException exception = assertThrows(
                ProjectNotFoundException.class,
                () -> service.getByName(name));

        assertEquals("Project not found: name=" + name, exception.getMessage());

        verify(projectRepository).findByName(name);
    }
}

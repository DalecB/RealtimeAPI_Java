package com.jake.realtimeapi.projects.domain.repository;

import com.jake.realtimeapi.projects.domain.model.Project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {

    Project save(Project project);

    boolean existsByName(String name);

    Optional<Project> findById(UUID id);

    Optional<Project> findByName(String name);

    List<Project> findByAdminId(Long adminId);

    List<Project> findPage(int offset, int limit);

    long count();
}

package com.jake.realtimeapi.projects.persistence;

import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.persistence.entity.ProjectJpaEntity;

public final class ProjectPersistenceMapper {

    private ProjectPersistenceMapper() {}

    public static Project toDomain(ProjectJpaEntity entity) {
        return new Project(entity.getId(), entity.getAdminId(), entity.getName(), entity.getCreatedAt());
    }

    public static ProjectJpaEntity toEntity(Project project) {
        return new ProjectJpaEntity(project.id(), project.adminId(), project.createdAt(), project.name());
    }
}

package com.jake.realtimeapi.projects.persistence;

import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.projects.persistence.repository.SpringDataProjectJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectRepositoryAdapter implements ProjectRepository {

    private final SpringDataProjectJpaRepository jpaRepository;

    public ProjectRepositoryAdapter(SpringDataProjectJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Project save(Project project) {
        return ProjectPersistenceMapper.toDomain(jpaRepository.save(ProjectPersistenceMapper.toEntity(project)));
    }

    @Override
    public boolean existsByName(String name) {
        return jpaRepository.existsByName(name);
    }

    @Override
    public Optional<Project> findById(UUID id) {
        return jpaRepository.findById(id).map(ProjectPersistenceMapper::toDomain);
    }

    @Override
    public Optional<Project> findByName(String name) {
        return jpaRepository.findByName(name).map(ProjectPersistenceMapper::toDomain);
    }

    @Override
    public List<Project> findByAdminId(Long adminId) {
        return jpaRepository.findByAdminId(adminId).stream()
                .map(ProjectPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<Project> findPage(int offset, int limit) {
        return jpaRepository.findPage(offset, limit).stream()
                .map(ProjectPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }
}

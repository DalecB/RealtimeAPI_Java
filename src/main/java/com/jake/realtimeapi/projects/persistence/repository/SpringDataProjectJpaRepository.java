package com.jake.realtimeapi.projects.persistence.repository;

import com.jake.realtimeapi.projects.persistence.entity.ProjectJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataProjectJpaRepository extends JpaRepository<ProjectJpaEntity, UUID> {

    boolean existsByName(String name);

    Optional<ProjectJpaEntity> findByName(String name);

    List<ProjectJpaEntity> findByAdminId(Long adminId);

    @Query(value = "SELECT * FROM projects ORDER BY created_at OFFSET :offset LIMIT :limit", nativeQuery = true)
    List<ProjectJpaEntity> findPage(@Param("offset") int offset, @Param("limit") int limit);
}

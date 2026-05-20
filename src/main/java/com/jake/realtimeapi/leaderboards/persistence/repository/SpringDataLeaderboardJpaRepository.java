package com.jake.realtimeapi.leaderboards.persistence.repository;

import com.jake.realtimeapi.leaderboards.persistence.entity.LeaderboardJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataLeaderboardJpaRepository extends JpaRepository<LeaderboardJpaEntity, UUID> {

    boolean existsByProjectIdAndName(UUID projectId, String name);

    Optional<LeaderboardJpaEntity> findByProjectIdAndName(UUID projectId, String name);

    List<LeaderboardJpaEntity> findByProjectId(UUID projectId);

    @Query(value = "SELECT * FROM leaderboards ORDER BY created_at OFFSET :offset LIMIT :limit", nativeQuery = true)
    List<LeaderboardJpaEntity> findPage(@Param("offset") int offset, @Param("limit") int limit);

    @Query("SELECT l.id FROM LeaderboardJpaEntity l ORDER BY l.createdAt ASC")
    List<UUID> findAllIds();
}

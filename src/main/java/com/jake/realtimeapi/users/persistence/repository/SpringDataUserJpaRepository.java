package com.jake.realtimeapi.users.persistence.repository;

import com.jake.realtimeapi.users.persistence.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataUserJpaRepository extends JpaRepository<UserJpaEntity, Long> {

    Optional<UserJpaEntity> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);

    @Query(value = "SELECT * FROM users ORDER BY id OFFSET :offset LIMIT :limit", nativeQuery = true)
    List<UserJpaEntity> findPage(@Param("offset") int offset, @Param("limit") int limit);
}

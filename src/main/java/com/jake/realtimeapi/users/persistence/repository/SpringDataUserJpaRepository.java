package com.jake.realtimeapi.users.persistence.repository;

import com.jake.realtimeapi.users.persistence.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository.
 *
 * <p>이 인터페이스는 구현 클래스를 직접 작성하지 않아도 됩니다.
 * 스프링이 런타임에 프록시 구현체를 자동 생성합니다.
 *
 * <p>메서드 유형:
 * - findBy..., existsBy... : 메서드명 파싱으로 쿼리 자동 생성
 * - @Query : 개발자가 SQL/JPQL을 직접 지정
 */
public interface SpringDataUserJpaRepository extends JpaRepository<UserJpaEntity, Long> {

    /**
     * external_id 컬럼 기반 단건 조회(자동 파생 쿼리).
     */
    Optional<UserJpaEntity> findByExternalId(String externalId);

    /**
     * external_id 존재 여부 조회(자동 파생 쿼리).
     */
    boolean existsByExternalId(String externalId);

    /**
     * offset/limit 기반 목록 조회(명시 SQL).
     *
     * <p>단순 예시로 native query를 사용했습니다.
     */
    @Query(value = "SELECT * FROM users ORDER BY id OFFSET :offset LIMIT :limit", nativeQuery = true)
    List<UserJpaEntity> findPage(@Param("offset") int offset, @Param("limit") int limit);
}

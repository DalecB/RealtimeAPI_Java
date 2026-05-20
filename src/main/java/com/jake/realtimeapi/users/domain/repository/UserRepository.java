package com.jake.realtimeapi.users.domain.repository;

import com.jake.realtimeapi.users.domain.model.User;
import java.util.List;
import java.util.Optional;

/**
 * Users 도메인 저장소 추상화(Port).
 *
 * <p>domain/application 레이어는 DB 기술(JPA/SQL)을 몰라도 되도록
 * 이 인터페이스에만 의존합니다.
 */
public interface UserRepository {

    /**
     * 유저를 저장합니다.
     *
     * @param user 저장할 도메인 유저
     * @return 저장 후 상태(생성된 id 포함)
     */
    User save(User user);

    /**
     * externalId 중복 여부를 확인합니다.
     */
    boolean existsByExternalId(String externalId);

    /**
     * 내부 PK로 조회합니다.
     */
    Optional<User> findById(Long id);

    /**
     * externalId로 조회합니다.
     */
    Optional<User> findByExternalId(String externalId);

    /**
     * offset/limit 기반 페이지 조회.
     */
    List<User> findPage(int offset, int limit);

    /**
     * 전체 유저 수를 반환합니다.
     */
    long count();
}

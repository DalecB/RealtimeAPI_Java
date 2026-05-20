package com.jake.realtimeapi.users.persistence;

import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import com.jake.realtimeapi.users.persistence.repository.SpringDataUserJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
/**
 * UserRepository(domain port)의 persistence adapter 구현체.
 *
 * <p>역할:
 * 1) domain repository 호출을 Spring Data JPA 호출로 변환
 * 2) domain model <-> entity 매핑
 *
 * <p>즉, application/domain은 이 클래스 덕분에 JPA에 직접 의존하지 않습니다.
 */
public class UserRepositoryAdapter implements UserRepository {

    private final SpringDataUserJpaRepository jpaRepository;

    public UserRepositoryAdapter(SpringDataUserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    /**
     * 유저 저장.
     * domain -> entity 변환 후 저장하고, 저장 결과를 다시 domain으로 변환합니다.
     */
    public User save(User user) {
        return UserPersistenceMapper.toDomain(jpaRepository.save(UserPersistenceMapper.toEntity(user)));
    }

    @Override
    /**
     * externalId 존재 여부 확인.
     */
    public boolean existsByExternalId(String externalId) {
        return jpaRepository.existsByExternalId(externalId);
    }

    @Override
    /**
     * 내부 PK 기반 조회.
     */
    public Optional<User> findById(Long id) {
        return jpaRepository.findById(id).map(UserPersistenceMapper::toDomain);
    }

    @Override
    /**
     * externalId 기반 조회.
     */
    public Optional<User> findByExternalId(String externalId) {
        return jpaRepository.findByExternalId(externalId).map(UserPersistenceMapper::toDomain);
    }

    @Override
    /**
     * offset/limit 페이지 조회.
     */
    public List<User> findPage(int offset, int limit) {
        return jpaRepository.findPage(offset, limit).stream()
                .map(UserPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    /**
     * 전체 유저 수 조회.
     */
    public long count() {
        return jpaRepository.count();
    }
}

package com.jake.realtimeapi.users.persistence;

import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import com.jake.realtimeapi.users.persistence.repository.SpringDataUserJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepositoryAdapter implements UserRepository {

    private final SpringDataUserJpaRepository jpaRepository;

    public UserRepositoryAdapter(SpringDataUserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public User save(User user) {
        return UserPersistenceMapper.toDomain(jpaRepository.save(UserPersistenceMapper.toEntity(user)));
    }

    @Override
    public boolean existsByExternalId(String externalId) {
        return jpaRepository.existsByExternalId(externalId);
    }

    @Override
    public Optional<User> findById(Long id) {
        return jpaRepository.findById(id).map(UserPersistenceMapper::toDomain);
    }

    @Override
    public Optional<User> findByExternalId(String externalId) {
        return jpaRepository.findByExternalId(externalId).map(UserPersistenceMapper::toDomain);
    }

    @Override
    public List<User> findPage(int offset, int limit) {
        return jpaRepository.findPage(offset, limit).stream()
                .map(UserPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }
}

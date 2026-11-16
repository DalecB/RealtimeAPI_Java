package com.jake.realtimeapi.users.domain.repository;

import com.jake.realtimeapi.users.domain.model.User;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

    User save(User user);

    boolean existsByExternalId(String externalId);

    Optional<User> findById(Long id);

    Optional<User> findByExternalId(String externalId);

    List<User> findPage(int offset, int limit);

    long count();
}

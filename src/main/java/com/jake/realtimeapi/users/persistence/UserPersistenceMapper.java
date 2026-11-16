package com.jake.realtimeapi.users.persistence;

import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.persistence.entity.UserJpaEntity;

public final class UserPersistenceMapper {

    private UserPersistenceMapper() {
    }

    public static User toDomain(UserJpaEntity entity) {
        return new User(entity.getId(), entity.getExternalId(), entity.getCreatedAt());
    }

    public static UserJpaEntity toEntity(User user) {
        return new UserJpaEntity(user.id(), user.externalId(), user.createdAt());
    }
}

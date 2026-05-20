package com.jake.realtimeapi.users.persistence;

import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.persistence.entity.UserJpaEntity;

/**
 * domain <-> persistence 변환 전담 Mapper.
 *
 * <p>레이어 분리를 유지하기 위해 엔티티를 domain 밖으로 노출하지 않습니다.
 * 항상 mapper를 통해 서로 변환합니다.
 */
public final class UserPersistenceMapper {

    /**
     * 정적 유틸 클래스이므로 인스턴스화를 막습니다.
     */
    private UserPersistenceMapper() {
    }

    /**
     * JPA 엔티티 -> 도메인 모델 변환.
     */
    public static User toDomain(UserJpaEntity entity) {
        return new User(entity.getId(), entity.getExternalId(), entity.getCreatedAt());
    }

    /**
     * 도메인 모델 -> JPA 엔티티 변환.
     */
    public static UserJpaEntity toEntity(User user) {
        return new UserJpaEntity(user.id(), user.externalId(), user.createdAt());
    }
}

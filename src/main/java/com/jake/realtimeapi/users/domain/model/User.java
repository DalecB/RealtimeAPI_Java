package com.jake.realtimeapi.users.domain.model;

import java.time.Instant;

/**
 * Users 도메인의 핵심 모델.
 *
 * <p>이 record는 "유저가 어떤 속성을 가져야 하는지"와
 * "어떤 값은 허용되지 않는지"를 한 곳에서 표현합니다.
 *
 * @param id 내부 PK (신규 생성 전에는 null 가능)
 * @param externalId 외부 식별자(필수)
 * @param createdAt 생성 시각
 */
public record User(Long id, String externalId, Instant createdAt) {

    private static final int MAX_EXTERNAL_ID_LENGTH = 30;

    /**
     * 도메인 불변식 검증.
     *
     * <p>record 생성 시점마다 자동 호출되어, 잘못된 상태의 객체가
     * 시스템 안으로 들어오는 것을 early-fail로 막습니다.
     */
    public User {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
        if (externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw new IllegalArgumentException("externalId length must be <= " + MAX_EXTERNAL_ID_LENGTH);
        }
    }

    /**
     * 신규 유저 생성용 팩토리 메서드.
     *
     * <p>DB가 id/createdAt을 채우므로 초기값은 null로 둡니다.
     */
    public static User newUser(String externalId) {
        return new User(null, externalId, null);
    }
}

package com.jake.realtimeapi.users.application.usecase;

import com.jake.realtimeapi.users.domain.model.User;

/**
 * 유저 조회 유스케이스 계약(포트).
 */
public interface GetUserUseCase {

    /**
     * 내부 ID로 유저를 조회합니다.
     *
     * @param id 내부 PK
     * @return 조회된 유저
     */
    User getById(Long id);

    /**
     * 외부 식별자로 유저를 조회합니다.
     *
     * @param externalId 외부 식별자
     * @return 조회된 유저
     */
    User getByExternalId(String externalId);
}

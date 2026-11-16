package com.jake.realtimeapi.auth.domain.repository;

import com.jake.realtimeapi.auth.domain.model.AdminAccount;

import java.util.Optional;

/**
 * 관리자 계정 포트. password_hash가 있는 users 행만 관리자로 본다.
 */
public interface AdminAccountRepository {

    Optional<AdminAccount> findByExternalId(String externalId);

    Optional<AdminAccount> findByUserId(long userId);

    /**
     * externalId 행이 없을 때만 관리자 행을 만든다. 이미 행이 있으면(관리자든 플레이어든) 바꾸지 않는다.
     *
     * @return 새로 만들었으면 true
     */
    boolean createIfAbsent(String externalId, String passwordHash);
}

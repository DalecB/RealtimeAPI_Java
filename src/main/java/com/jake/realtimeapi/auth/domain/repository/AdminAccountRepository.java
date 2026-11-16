package com.jake.realtimeapi.auth.domain.repository;

import com.jake.realtimeapi.auth.domain.model.AdminAccount;

import java.util.Optional;

/**
 * password_hash가 있는 users 행만 관리자로 본다.
 */
public interface AdminAccountRepository {

    Optional<AdminAccount> findByExternalId(String externalId);

    Optional<AdminAccount> findByUserId(long userId);

    /** 행이 이미 있으면 플레이어 행이라도 관리자로 바꾸지 않는다. */
    boolean createIfAbsent(String externalId, String passwordHash);
}

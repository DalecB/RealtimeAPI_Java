package com.jake.realtimeapi.users.domain.exception;

/**
 * 유저 생성 시 externalId 중복이 발견되면 발생시키는 도메인 예외.
 *
 * <p>API 레이어에서는 이 예외를 받아 409(CONFLICT)로 응답합니다.
 */
public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String externalId) {
        super("User already exists: externalId=" + externalId);
    }
}

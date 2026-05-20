package com.jake.realtimeapi.users.domain.exception;

/**
 * 조회 대상 유저가 없을 때 사용하는 도메인 예외.
 *
 * <p>API 레이어에서는 이 예외를 받아 404 응답으로 변환합니다.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("User not found: id=" + id);
    }

    public UserNotFoundException(String externalId) {
        super("User not found: externalId=" + externalId);
    }
}

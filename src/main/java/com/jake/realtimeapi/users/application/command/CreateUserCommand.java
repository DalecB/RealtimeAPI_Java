package com.jake.realtimeapi.users.application.command;

/**
 * 유저 생성 유스케이스 입력(Command) 모델.
 *
 * <p>API 요청 DTO를 애플리케이션 레이어에서 사용하기 좋은 형태로 변환한 값 객체입니다.
 * 이 시점에서 최소한의 유효성(필수값)을 한 번 더 방어적으로 체크합니다.
 *
 * @param externalId 생성할 유저의 외부 식별자
 */
public record CreateUserCommand(String externalId) {

    /**
     * record 생성 시 자동 호출되는 compact constructor.
     * 외부 식별자가 비어 있으면 즉시 예외를 발생시켜 잘못된 흐름을 중단합니다.
     */
    public CreateUserCommand {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
    }
}

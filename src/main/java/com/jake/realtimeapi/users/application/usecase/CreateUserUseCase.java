package com.jake.realtimeapi.users.application.usecase;

import com.jake.realtimeapi.users.application.command.CreateUserCommand;
import com.jake.realtimeapi.users.domain.model.User;

/**
 * 유저 생성 유스케이스 계약(포트).
 *
 * <p>Controller는 구현체를 모르고 이 인터페이스만 의존합니다.
 * 이를 통해 API 레이어와 비즈니스 로직 레이어를 느슨하게 결합합니다.
 */
public interface CreateUserUseCase {

    /**
     * 유저를 생성합니다.
     *
     * @param command 생성 입력
     * @return 생성된 도메인 유저
     */
    User create(CreateUserCommand command);
}

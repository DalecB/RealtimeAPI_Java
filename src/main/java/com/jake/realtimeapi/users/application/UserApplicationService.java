package com.jake.realtimeapi.users.application;

import com.jake.realtimeapi.users.application.command.CreateUserCommand;
import com.jake.realtimeapi.users.application.usecase.CreateUserUseCase;
import com.jake.realtimeapi.users.application.usecase.GetUserUseCase;
import com.jake.realtimeapi.users.application.usecase.ListUsersUseCase;
import com.jake.realtimeapi.users.application.usecase.UserSlice;
import com.jake.realtimeapi.users.domain.exception.UserAlreadyExistsException;
import com.jake.realtimeapi.users.domain.exception.UserNotFoundException;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
/**
 * Users 도메인의 애플리케이션 서비스(유스케이스 구현체).
 *
 * <p>핵심 역할:
 * 1) 유스케이스 단위 로직 조합
 * 2) 트랜잭션 경계 관리
 * 3) 도메인 예외를 의미 있는 형태로 발생
 *
 * <p>중요: DB 접근 상세 구현은 persistence adapter에 위임합니다.
 */
public class UserApplicationService implements CreateUserUseCase, GetUserUseCase, ListUsersUseCase {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final UserRepository userRepository;

    public UserApplicationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    /**
     * 유저 생성 흐름.
     *
     * <p>순서:
     * 1) externalId 중복 여부 확인
     * 2) 중복이면 도메인 예외 발생
     * 3) 신규 유저 도메인 객체 생성 및 저장
     */
    public User create(CreateUserCommand command) {
        if (userRepository.existsByExternalId(command.externalId())) {
            throw new UserAlreadyExistsException(command.externalId());
        }
        return userRepository.save(User.newUser(command.externalId()));
    }

    @Override
    /**
     * 내부 ID 기반 유저 조회.
     *
     * <p>Optional을 바로 반환하지 않고, 없을 경우 도메인 예외를 발생시켜
     * 상위(API)에서 일관된 404 응답으로 변환할 수 있게 합니다.
     */
    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Override
    /**
     * externalId 기반 유저 조회.
     */
    public User getByExternalId(String externalId) {
        return userRepository.findByExternalId(externalId)
                .orElseThrow(() -> new UserNotFoundException(externalId));
    }

    @Override
    /**
     * 유저 목록 조회.
     *
     * <p>offset/limit를 안전한 범위로 정규화한 뒤 조회합니다.
     * - offset은 0 미만 방지
     * - limit은 기본값/최댓값 강제
     */
    public UserSlice list(int offset, int limit) {
        int normalizedOffset = Math.max(0, offset);
        int normalizedLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return new UserSlice(userRepository.findPage(normalizedOffset, normalizedLimit), normalizedOffset, normalizedLimit);
    }
}

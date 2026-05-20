package com.jake.realtimeapi.users.api;

import com.jake.realtimeapi.users.api.dto.CreateUserRequest;
import com.jake.realtimeapi.users.api.dto.ListUsersResponse;
import com.jake.realtimeapi.users.api.dto.UserResponse;
import com.jake.realtimeapi.users.application.command.CreateUserCommand;
import com.jake.realtimeapi.users.application.usecase.CreateUserUseCase;
import com.jake.realtimeapi.users.application.usecase.GetUserUseCase;
import com.jake.realtimeapi.users.application.usecase.ListUsersUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
/**
 * Users 도메인의 HTTP 진입점(Controller)입니다.
 *
 * <p>이 클래스의 책임은 딱 두 가지입니다.
 * 1) HTTP 요청을 애플리케이션 유스케이스 입력으로 변환
 * 2) 유스케이스 결과를 HTTP 응답 DTO로 변환
 *
 * <p>비즈니스 규칙(중복 체크, 조회 실패 처리 등)은 여기에서 구현하지 않고
 * application/domain 레이어에 위임합니다.
 */
public class UserController {

    private final CreateUserUseCase createUserUseCase;
    private final GetUserUseCase getUserUseCase;
    private final ListUsersUseCase listUsersUseCase;

    public UserController(
            CreateUserUseCase createUserUseCase,
            GetUserUseCase getUserUseCase,
            ListUsersUseCase listUsersUseCase
    ) {
        this.createUserUseCase = createUserUseCase;
        this.getUserUseCase = getUserUseCase;
        this.listUsersUseCase = listUsersUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * 유저를 생성합니다.
     *
     * <p>처리 순서:
     * 1) JSON body를 CreateUserRequest로 역직렬화
     * 2) @Valid로 입력값 검증
     * 3) Command 객체로 변환 후 유스케이스 호출
     * 4) 도메인 모델을 응답 DTO(UserResponse)로 변환
     *
     * @param request 생성 요청 데이터
     * @return 생성된 유저 정보
     */
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return UserApiMapper.toResponse(createUserUseCase.create(new CreateUserCommand(request.externalId())));
    }

    @GetMapping("/{userId}")
    /**
     * ID(PK)로 유저를 조회합니다.
     *
     * @param userId 조회할 유저의 내부 식별자
     * @return 유저 정보
     */
    public UserResponse getById(@PathVariable Long userId) {
        return UserApiMapper.toResponse(getUserUseCase.getById(userId));
    }

    @GetMapping("/external/{externalId}")
    /**
     * 외부 식별자(externalId)로 유저를 조회합니다.
     *
     * @param externalId 외부 시스템/클라이언트에서 사용하는 식별자
     * @return 유저 정보
     */
    public UserResponse getByExternalId(@PathVariable String externalId) {
        return UserApiMapper.toResponse(getUserUseCase.getByExternalId(externalId));
    }

    @GetMapping
    /**
     * 유저 목록을 페이지 형태(offset/limit)로 조회합니다.
     *
     * @param offset 시작 위치(0부터)
     * @param limit 반환 개수
     * @return 유저 목록 응답
     */
    public ListUsersResponse list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return UserApiMapper.toListResponse(listUsersUseCase.list(offset, limit));
    }
}

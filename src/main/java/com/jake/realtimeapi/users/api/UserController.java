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
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return UserApiMapper.toResponse(createUserUseCase.create(new CreateUserCommand(request.externalId())));
    }

    @GetMapping("/{userId}")
    public UserResponse getById(@PathVariable Long userId) {
        return UserApiMapper.toResponse(getUserUseCase.getById(userId));
    }

    @GetMapping("/external/{externalId}")
    public UserResponse getByExternalId(@PathVariable String externalId) {
        return UserApiMapper.toResponse(getUserUseCase.getByExternalId(externalId));
    }

    @GetMapping
    public ListUsersResponse list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return UserApiMapper.toListResponse(listUsersUseCase.list(offset, limit));
    }
}

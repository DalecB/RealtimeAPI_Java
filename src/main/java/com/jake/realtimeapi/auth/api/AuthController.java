package com.jake.realtimeapi.auth.api;

import com.jake.realtimeapi.auth.api.dto.AdminLoginRequest;
import com.jake.realtimeapi.auth.api.dto.AdminLoginResponse;
import com.jake.realtimeapi.auth.application.command.AdminLoginCommand;
import com.jake.realtimeapi.auth.application.model.AdminLoginResult;
import com.jake.realtimeapi.auth.application.usecase.AdminLoginUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AdminLoginUseCase adminLoginUseCase;

    public AuthController(AdminLoginUseCase adminLoginUseCase) {
        this.adminLoginUseCase = adminLoginUseCase;
    }

    @PostMapping("/login")
    public AdminLoginResponse login(@Valid @RequestBody AdminLoginRequest request) {
        AdminLoginResult result = adminLoginUseCase.login(new AdminLoginCommand(request.externalId(), request.password()));
        return new AdminLoginResponse(
                result.accessToken(),
                result.tokenType(),
                result.expiresAt(),
                result.userId(),
                result.externalId()
        );
    }
}

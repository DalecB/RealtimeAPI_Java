package com.jake.realtimeapi.apikeys.api;

import com.jake.realtimeapi.auth.api.CurrentAdminUser;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import com.jake.realtimeapi.apikeys.api.dto.ApiKeyResponse;
import com.jake.realtimeapi.apikeys.api.dto.CreateApiKeyRequest;
import com.jake.realtimeapi.apikeys.application.command.CreateApiKeyCommand;
import com.jake.realtimeapi.apikeys.application.model.IssuedApiKeyResult;
import com.jake.realtimeapi.apikeys.application.usecase.CreateApiKeyUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api-keys")
public class AdminApiKeyController {

    private final CreateApiKeyUseCase createApiKeyUseCase;

    public AdminApiKeyController(CreateApiKeyUseCase createApiKeyUseCase) {
        this.createApiKeyUseCase = createApiKeyUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyResponse create(
            @CurrentAdminUser AuthenticatedAdminUser currentAdminUser,
            @Valid @RequestBody CreateApiKeyRequest request
    ) {
        IssuedApiKeyResult result = createApiKeyUseCase.create(
                new CreateApiKeyCommand(
                        request.projectId(),
                        currentAdminUser.userId(),
                        request.rateLimitPerSec(),
                        request.dailyQuota(),
                        request.expiresAt()
                )
        );

        return new ApiKeyResponse(
                result.id(),
                result.projectId(),
                result.rawKey(),
                result.keyPrefix(),
                result.status().name(),
                result.rateLimitPerSec(),
                result.dailyQuota(),
                result.createdAt(),
                result.expiresAt()
        );
    }
}

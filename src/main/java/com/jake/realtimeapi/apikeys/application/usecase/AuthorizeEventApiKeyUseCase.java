package com.jake.realtimeapi.apikeys.application.usecase;

import com.jake.realtimeapi.apikeys.application.model.AuthorizedApiKeyResult;

public interface AuthorizeEventApiKeyUseCase {

    AuthorizedApiKeyResult authorize(String authorizationHeader);
}

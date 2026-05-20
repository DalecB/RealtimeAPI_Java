package com.jake.realtimeapi.apikeys.application.usecase;

import com.jake.realtimeapi.apikeys.application.command.CreateApiKeyCommand;
import com.jake.realtimeapi.apikeys.application.model.IssuedApiKeyResult;

public interface CreateApiKeyUseCase {

    IssuedApiKeyResult create(CreateApiKeyCommand command);
}

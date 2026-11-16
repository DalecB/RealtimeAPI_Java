package com.jake.realtimeapi.users.application.usecase;

import com.jake.realtimeapi.users.domain.model.User;

public interface GetUserUseCase {

    User getById(Long id);

    User getByExternalId(String externalId);
}

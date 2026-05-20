package com.jake.realtimeapi.auth.application.usecase;

import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;

public interface AuthenticateAdminJwtUseCase {

    AuthenticatedAdminUser authenticate(String authorizationHeader);
}

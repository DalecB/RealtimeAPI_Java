package com.jake.realtimeapi.auth.application.usecase;

import com.jake.realtimeapi.auth.application.command.AdminLoginCommand;
import com.jake.realtimeapi.auth.application.model.AdminLoginResult;

public interface AdminLoginUseCase {

    AdminLoginResult login(AdminLoginCommand command);
}

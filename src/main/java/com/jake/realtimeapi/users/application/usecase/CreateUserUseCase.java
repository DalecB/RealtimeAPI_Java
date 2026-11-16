package com.jake.realtimeapi.users.application.usecase;

import com.jake.realtimeapi.users.application.command.CreateUserCommand;
import com.jake.realtimeapi.users.domain.model.User;

public interface CreateUserUseCase {

    User create(CreateUserCommand command);
}

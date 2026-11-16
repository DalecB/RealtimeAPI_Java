package com.jake.realtimeapi.users.application.usecase;

public interface ListUsersUseCase {

    UserSlice list(int offset, int limit);
}

package com.jake.realtimeapi.users.api;

import com.jake.realtimeapi.users.api.dto.ListUsersResponse;
import com.jake.realtimeapi.users.api.dto.UserResponse;
import com.jake.realtimeapi.users.application.usecase.UserSlice;
import com.jake.realtimeapi.users.domain.model.User;
import java.util.List;

public final class UserApiMapper {

    private UserApiMapper() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(user.id(), user.externalId(), user.createdAt());
    }

    public static ListUsersResponse toListResponse(UserSlice slice) {
        List<UserResponse> items = slice.users().stream()
                .map(UserApiMapper::toResponse)
                .toList();
        return new ListUsersResponse(items, slice.offset(), slice.limit(), items.size());
    }
}

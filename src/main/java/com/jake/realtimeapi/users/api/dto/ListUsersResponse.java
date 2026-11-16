package com.jake.realtimeapi.users.api.dto;

import java.util.List;

public record ListUsersResponse(
        List<UserResponse> items,
        int offset,
        int limit,
        int returnedCount
) {
}

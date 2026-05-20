package com.jake.realtimeapi.users.api.dto;

import java.util.List;

/**
 * 유저 목록 응답 DTO.
 *
 * @param items 반환된 유저 목록
 * @param offset 시작 위치
 * @param limit 요청 limit(정규화 후 값)
 * @param returnedCount 실제 반환 건수
 */
public record ListUsersResponse(
        List<UserResponse> items,
        int offset,
        int limit,
        int returnedCount
) {
}

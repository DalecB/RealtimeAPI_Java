package com.jake.realtimeapi.users.api;

import com.jake.realtimeapi.users.api.dto.ListUsersResponse;
import com.jake.realtimeapi.users.api.dto.UserResponse;
import com.jake.realtimeapi.users.application.usecase.UserSlice;
import com.jake.realtimeapi.users.domain.model.User;
import java.util.List;

public final class UserApiMapper {

    /**
     * 정적 유틸 클래스이므로 인스턴스 생성을 막습니다.
     */
    private UserApiMapper() {
    }

    /**
     * 도메인 모델(User) -> API 응답 모델(UserResponse) 변환.
     *
     * <p>Controller는 도메인 객체를 외부에 직접 노출하지 않고,
     * 응답 계약(Response DTO)으로 변환해서 반환해야 합니다.
     *
     * @param user 도메인 유저 객체
     * @return API 응답 DTO
     */
    public static UserResponse toResponse(User user) {
        return new UserResponse(user.id(), user.externalId(), user.createdAt());
    }

    /**
     * 페이지 조회 결과(UserSlice) -> 목록 응답(ListUsersResponse) 변환.
     *
     * <p>items 리스트뿐 아니라 offset/limit/returnedCount 같은 메타 정보도 함께 반환합니다.
     *
     * @param slice 애플리케이션 레이어에서 계산된 목록 결과
     * @return API 목록 응답 DTO
     */
    public static ListUsersResponse toListResponse(UserSlice slice) {
        List<UserResponse> items = slice.users().stream()
                .map(UserApiMapper::toResponse)
                .toList();
        return new ListUsersResponse(items, slice.offset(), slice.limit(), items.size());
    }
}

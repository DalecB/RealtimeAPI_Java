package com.jake.realtimeapi.users.application.usecase;

/**
 * 유저 목록 조회 유스케이스 계약(포트).
 */
public interface ListUsersUseCase {

    /**
     * offset/limit 기반으로 유저 목록을 조회합니다.
     *
     * @param offset 시작 위치
     * @param limit 조회 개수
     * @return 목록 결과 + 페이징 메타 정보
     */
    UserSlice list(int offset, int limit);
}

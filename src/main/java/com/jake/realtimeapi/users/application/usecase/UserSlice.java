package com.jake.realtimeapi.users.application.usecase;

import com.jake.realtimeapi.users.domain.model.User;
import java.util.List;

/**
 * 목록 조회 결과를 표현하는 record.
 *
 * @param users 실제 조회된 유저 목록
 * @param offset 시작 위치
 * @param limit 적용된 조회 개수
 */
public record UserSlice(List<User> users, int offset, int limit) {
}

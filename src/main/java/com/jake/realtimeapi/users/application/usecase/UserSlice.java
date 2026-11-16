package com.jake.realtimeapi.users.application.usecase;

import com.jake.realtimeapi.users.domain.model.User;
import java.util.List;

public record UserSlice(List<User> users, int offset, int limit) {
}

package com.jake.realtimeapi.support.userid;

public final class UserIdCodec {

    private UserIdCodec() {
    }

    public static long parse(String rawUserId) {
        try {
            long userId = Long.parseLong(rawUserId);
            if (userId <= 0) {
                throw new IllegalArgumentException("userId must be a positive integer");
            }
            return userId;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("userId must be a positive integer", e);
        }
    }

    public static String format(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be a positive integer");
        }
        return Long.toString(userId);
    }
}

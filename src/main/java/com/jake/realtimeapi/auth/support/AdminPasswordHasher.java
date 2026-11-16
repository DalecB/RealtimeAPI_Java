package com.jake.realtimeapi.auth.support;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminPasswordHasher {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    // 계정이 없어도 BCrypt 비교를 한 번 돌려서, 응답 시간으로 계정 존재 여부가 드러나지 않게 한다.
    private final String dummyHash = encoder.encode("admin-login-timing-dummy");

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        String candidate = rawPassword == null ? "" : rawPassword;
        if (passwordHash == null) {
            encoder.matches(candidate, dummyHash);
            return false;
        }
        return encoder.matches(candidate, passwordHash);
    }
}

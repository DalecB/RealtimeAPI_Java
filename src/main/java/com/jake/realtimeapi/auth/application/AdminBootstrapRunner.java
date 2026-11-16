package com.jake.realtimeapi.auth.application;

import com.jake.realtimeapi.auth.domain.repository.AdminAccountRepository;
import com.jake.realtimeapi.auth.support.AdminPasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 관리자 계정은 이 시드로만 만든다. 비밀번호 설정이 없으면 아무 계정도 만들지 않는다.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminAccountRepository adminAccountRepository;
    private final AdminPasswordHasher passwordHasher;
    private final String username;
    private final String password;

    public AdminBootstrapRunner(
            AdminAccountRepository adminAccountRepository,
            AdminPasswordHasher passwordHasher,
            @Value("${app.admin.bootstrap.username:admin}") String username,
            @Value("${app.admin.bootstrap.password:}") String password
    ) {
        this.adminAccountRepository = adminAccountRepository;
        this.passwordHasher = passwordHasher;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank() || username == null || username.isBlank()) {
            log.info("admin.bootstrap skipped reason=password-not-configured");
            return;
        }

        if (adminAccountRepository.createIfAbsent(username, passwordHasher.hash(password))) {
            log.info("admin.bootstrap created username={}", username);
            return;
        }

        if (adminAccountRepository.findByExternalId(username).isPresent()) {
            log.info("admin.bootstrap skipped reason=already-exists username={}", username);
        } else {
            // POST /users로 만든 플레이어 행을 관리자로 승격하지 않는다.
            log.warn("admin.bootstrap skipped reason=player-with-same-external-id username={}", username);
        }
    }
}

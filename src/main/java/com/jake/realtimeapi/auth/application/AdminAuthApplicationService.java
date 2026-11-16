package com.jake.realtimeapi.auth.application;

import com.jake.realtimeapi.auth.application.command.AdminLoginCommand;
import com.jake.realtimeapi.auth.application.model.AdminLoginResult;
import com.jake.realtimeapi.auth.application.usecase.AdminLoginUseCase;
import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.auth.domain.exception.AdminAuthenticationException;
import com.jake.realtimeapi.auth.domain.model.AdminAccount;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import com.jake.realtimeapi.auth.domain.repository.AdminAccountRepository;
import com.jake.realtimeapi.auth.support.AdminPasswordHasher;
import com.jake.realtimeapi.auth.support.JwtTokenCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AdminAuthApplicationService implements AdminLoginUseCase, AuthenticateAdminJwtUseCase {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AdminAccountRepository adminAccountRepository;
    private final AdminPasswordHasher passwordHasher;
    private final JwtTokenCodec jwtTokenCodec;

    public AdminAuthApplicationService(
            AdminAccountRepository adminAccountRepository,
            AdminPasswordHasher passwordHasher,
            JwtTokenCodec jwtTokenCodec
    ) {
        this.adminAccountRepository = adminAccountRepository;
        this.passwordHasher = passwordHasher;
        this.jwtTokenCodec = jwtTokenCodec;
    }

    @Override
    public AdminLoginResult login(AdminLoginCommand command) {
        // 계정 없음·플레이어 계정·비밀번호 누락·불일치를 모두 같은 예외로 돌려 어느 쪽이 틀렸는지 숨긴다.
        Optional<AdminAccount> account = adminAccountRepository.findByExternalId(command.externalId());
        boolean matched = passwordHasher.matches(command.password(), account.map(AdminAccount::passwordHash).orElse(null));
        if (account.isEmpty() || !matched) {
            throw new AdminAuthenticationException("admin login is invalid");
        }

        AuthenticatedAdminUser authenticatedUser = new AuthenticatedAdminUser(account.get().userId(), account.get().externalId());
        JwtTokenCodec.IssuedToken issuedToken = jwtTokenCodec.issue(authenticatedUser);
        return new AdminLoginResult(
                issuedToken.accessToken(),
                "Bearer",
                issuedToken.expiresAt(),
                authenticatedUser.userId(),
                authenticatedUser.externalId()
        );
    }

    @Override
    public AuthenticatedAdminUser authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new AdminAuthenticationException("Authorization header must use Bearer token");
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new AdminAuthenticationException("Authorization header must use Bearer token");
        }

        AuthenticatedAdminUser authenticatedUser = jwtTokenCodec.parse(token);
        var account = adminAccountRepository.findByUserId(authenticatedUser.userId())
                .orElseThrow(() -> new AdminAuthenticationException("admin user is invalid"));
        return new AuthenticatedAdminUser(account.userId(), account.externalId());
    }
}

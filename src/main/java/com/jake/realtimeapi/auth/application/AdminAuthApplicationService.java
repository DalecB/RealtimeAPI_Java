package com.jake.realtimeapi.auth.application;

import com.jake.realtimeapi.auth.application.command.AdminLoginCommand;
import com.jake.realtimeapi.auth.application.model.AdminLoginResult;
import com.jake.realtimeapi.auth.application.usecase.AdminLoginUseCase;
import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.auth.domain.exception.AdminAuthenticationException;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import com.jake.realtimeapi.auth.support.JwtTokenCodec;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminAuthApplicationService implements AdminLoginUseCase, AuthenticateAdminJwtUseCase {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserRepository userRepository;
    private final JwtTokenCodec jwtTokenCodec;

    public AdminAuthApplicationService(UserRepository userRepository, JwtTokenCodec jwtTokenCodec) {
        this.userRepository = userRepository;
        this.jwtTokenCodec = jwtTokenCodec;
    }

    @Override
    public AdminLoginResult login(AdminLoginCommand command) {
        // TODO: admin login is password-less in the current scope.
        // Replace this externalId-only lookup with password/OAuth-based authentication when auth scope expands.
        var user = userRepository.findByExternalId(command.externalId())
                .orElseThrow(() -> new AdminAuthenticationException("admin login is invalid"));

        AuthenticatedAdminUser authenticatedUser = new AuthenticatedAdminUser(user.id(), user.externalId());
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
        var user = userRepository.findById(authenticatedUser.userId())
                .orElseThrow(() -> new AdminAuthenticationException("admin user is invalid"));
        return new AuthenticatedAdminUser(user.id(), user.externalId());
    }
}

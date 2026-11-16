package com.jake.realtimeapi.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.auth.application.command.AdminLoginCommand;
import com.jake.realtimeapi.auth.domain.exception.AdminAuthenticationException;
import com.jake.realtimeapi.auth.domain.model.AdminAccount;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import com.jake.realtimeapi.auth.domain.repository.AdminAccountRepository;
import com.jake.realtimeapi.auth.support.AdminPasswordHasher;
import com.jake.realtimeapi.auth.support.JwtTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthApplicationServiceTest {

    private static final AdminPasswordHasher HASHER = new AdminPasswordHasher();
    private static final String PASSWORD_HASH = HASHER.hash("correct-password");

    private AdminAccountRepository repository;
    private AdminPasswordHasher hasher;
    private JwtTokenCodec jwtTokenCodec;
    private AdminAuthApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminAccountRepository.class);
        hasher = spy(HASHER);
        jwtTokenCodec = new JwtTokenCodec(new ObjectMapper(), "test-secret", 3600);
        service = new AdminAuthApplicationService(repository, hasher, jwtTokenCodec);
        when(repository.findByExternalId("admin")).thenReturn(Optional.of(new AdminAccount(1L, "admin", PASSWORD_HASH)));
        when(repository.findByExternalId("unknown")).thenReturn(Optional.empty());
    }

    @Test
    void login_issuesTokenForCorrectPassword() {
        var result = service.login(new AdminLoginCommand("admin", "correct-password"));

        assertThat(result.userId()).isEqualTo(1L);
        assertThat(jwtTokenCodec.parse(result.accessToken())).isEqualTo(new AuthenticatedAdminUser(1L, "admin"));
    }

    @Test
    void login_rejectsWrongOrMissingPassword() {
        assertInvalidLogin(new AdminLoginCommand("admin", "wrong-password"));
        assertInvalidLogin(new AdminLoginCommand("admin", null));
        assertInvalidLogin(new AdminLoginCommand("admin", ""));
        // BCrypt 입력 한도(72바이트)를 넘어도 400/500이 아니라 같은 로그인 실패여야 한다.
        assertInvalidLogin(new AdminLoginCommand("admin", "x".repeat(200)));
        assertInvalidLogin(new AdminLoginCommand("unknown", "x".repeat(200)));
    }

    @Test
    void login_unknownUserStillRunsBcryptAndReturnsSameError() {
        assertInvalidLogin(new AdminLoginCommand("unknown", "correct-password"));

        verify(hasher).matches("correct-password", null);
    }

    @Test
    void authenticate_rejectsTokenWhoseUserIsNoLongerAdmin() {
        String token = jwtTokenCodec.issue(new AuthenticatedAdminUser(7L, "player")).accessToken();
        when(repository.findByUserId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("Bearer " + token))
                .isInstanceOf(AdminAuthenticationException.class);
    }

    private void assertInvalidLogin(AdminLoginCommand command) {
        assertThatThrownBy(() -> service.login(command))
                .isInstanceOf(AdminAuthenticationException.class)
                .hasMessage("admin login is invalid");
    }
}

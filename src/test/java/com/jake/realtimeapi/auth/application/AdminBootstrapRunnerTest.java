package com.jake.realtimeapi.auth.application;

import com.jake.realtimeapi.auth.domain.repository.AdminAccountRepository;
import com.jake.realtimeapi.auth.support.AdminPasswordHasher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminBootstrapRunnerTest {

    private final AdminAccountRepository repository = mock(AdminAccountRepository.class);
    private final AdminPasswordHasher hasher = new AdminPasswordHasher();

    @Test
    void run_withoutPassword_createsNoAdmin() {
        new AdminBootstrapRunner(repository, hasher, "admin", "").run(null);

        verifyNoInteractions(repository);
    }

    @Test
    void run_withPassword_storesBcryptHashNotPlaintext() {
        new AdminBootstrapRunner(repository, hasher, "admin", "secret-password").run(null);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).createIfAbsent(eq("admin"), hash.capture());
        assertThat(hash.getValue()).startsWith("$2a$").isNotEqualTo("secret-password");
        assertThat(hasher.matches("secret-password", hash.getValue())).isTrue();
    }
}

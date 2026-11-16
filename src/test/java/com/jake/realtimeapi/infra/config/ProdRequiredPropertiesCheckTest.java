package com.jake.realtimeapi.infra.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProdRequiredPropertiesCheckTest {

    @Test
    void prodProfile_failsWithoutJwtSecretEnv() {
        Exception exception = assertThrows(Exception.class, () -> startProd(Map.of(
                "SPRING_DATASOURCE_USERNAME", "prod-user",
                "SPRING_DATASOURCE_PASSWORD", "prod-password"
        )).close());

        assertTrue(rootMessage(exception).contains("APP_AUTH_JWT_SECRET"), rootMessage(exception));
    }

    @Test
    void prodProfile_failsWithoutDatasourcePasswordEnv() {
        Exception exception = assertThrows(Exception.class, () -> startProd(Map.of(
                "APP_AUTH_JWT_SECRET", "prod-secret",
                "SPRING_DATASOURCE_USERNAME", "prod-user"
        )).close());

        assertTrue(rootMessage(exception).contains("SPRING_DATASOURCE_PASSWORD"), rootMessage(exception));
    }

    @Test
    void prodProfile_startsWhenAllEnvProvided() {
        assertDoesNotThrow(() -> startProd(Map.of(
                "APP_AUTH_JWT_SECRET", "prod-secret",
                "SPRING_DATASOURCE_USERNAME", "prod-user",
                "SPRING_DATASOURCE_PASSWORD", "prod-password"
        )).close());
    }

    // 실제 OS 환경 변수 대신 주어진 맵만 환경 변수로 보이게 해서, relaxed binding 경로를 그대로 탄다.
    private ConfigurableApplicationContext startProd(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env)
        );
        return new SpringApplicationBuilder(ProdRequiredPropertiesCheck.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .run();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }
}

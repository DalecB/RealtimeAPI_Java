package com.jake.realtimeapi.infra.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

// @ConfigurationProperties 바인딩은 안 풀린 placeholder를 "${...}" 문자열 그대로 넘기므로 Environment 조회로 확인한다.
// 일반 빈이면 Flyway가 먼저 DB에 붙어 접속 실패로 죽으므로 일반 빈보다 먼저 만들어지는 BeanFactoryPostProcessor로 둔다.
@Component
@Profile("prod")
public class ProdRequiredPropertiesCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final List<String> REQUIRED = List.of(
            "app.auth.jwt.secret",
            "spring.datasource.username",
            "spring.datasource.password"
    );

    // 후처리기는 생성자 주입이 안 돼 EnvironmentAware 콜백에서 검사한다.
    @Override
    public void setEnvironment(Environment environment) {
        REQUIRED.forEach(environment::getRequiredProperty);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    }
}

package com.jake.realtimeapi.infra.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * prod 프로필에서 시크릿·DB 자격증명이 비어 있으면 기동을 막는다.
 *
 * <p>application-prod.properties가 이 값들을 기본값 없는 placeholder로 덮어쓴다. @ConfigurationProperties 바인딩은
 * 풀리지 않은 placeholder를 문자열 그대로 넘기므로(DB 비밀번호가 "${...}"가 됨) 바인딩에 맡기지 않고,
 * 풀리지 않으면 예외를 던지는 Environment 조회로 직접 확인한다.
 *
 * <p>BeanFactoryPostProcessor로 둔 이유는 순서다. 일반 빈이면 Flyway가 먼저 DB 접속을 시도해
 * "환경 변수 누락" 대신 접속 실패로 죽는다. 후처리기는 일반 빈보다 먼저 만들어진다.
 * 후처리기는 생성자 주입이 안 되므로 EnvironmentAware로 받는다.
 */
@Component
@Profile("prod")
public class ProdRequiredPropertiesCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final List<String> REQUIRED = List.of(
            "app.auth.jwt.secret",
            "spring.datasource.username",
            "spring.datasource.password"
    );

    @Override
    public void setEnvironment(Environment environment) {
        REQUIRED.forEach(environment::getRequiredProperty);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    }
}

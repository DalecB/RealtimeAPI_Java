package com.jake.realtimeapi.auth.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class AdminWebMvcConfig implements WebMvcConfigurer {

    private final AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    public AdminWebMvcConfig(AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase) {
        this.authenticateAdminJwtUseCase = authenticateAdminJwtUseCase;
    }

    @Bean
    public AdminAuthenticationInterceptor adminAuthenticationInterceptor() {
        return new AdminAuthenticationInterceptor(authenticateAdminJwtUseCase);
    }

    @Bean
    public CurrentAdminUserArgumentResolver currentAdminUserArgumentResolver() {
        return new CurrentAdminUserArgumentResolver();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthenticationInterceptor())
                .addPathPatterns("/projects", "/leaderboards", "/admin/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentAdminUserArgumentResolver());
    }
}

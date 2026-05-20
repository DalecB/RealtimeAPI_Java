package com.jake.realtimeapi.auth.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.auth.domain.exception.AdminAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class AdminAuthenticationInterceptor implements HandlerInterceptor {

    public static final String CURRENT_ADMIN_USER_ATTRIBUTE = "currentAdminUser";

    private final AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    public AdminAuthenticationInterceptor(AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase) {
        this.authenticateAdminJwtUseCase = authenticateAdminJwtUseCase;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!requiresAuthentication(request)) {
            return true;
        }

        var authenticatedUser = authenticateAdminJwtUseCase.authenticate(request.getHeader("Authorization"));
        if (authenticatedUser == null) {
            throw new AdminAuthenticationException("admin token is invalid");
        }
        request.setAttribute(CURRENT_ADMIN_USER_ATTRIBUTE, authenticatedUser);
        return true;
    }

    private boolean requiresAuthentication(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        if ("POST".equals(method) && "/projects".equals(uri)) {
            return true;
        }
        if ("POST".equals(method) && "/leaderboards".equals(uri)) {
            return true;
        }
        return "POST".equals(method) && "/admin/api-keys".equals(uri);
    }
}

package com.jake.realtimeapi.auth.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.auth.domain.exception.AdminAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class AdminAuthenticationInterceptor implements HandlerInterceptor {

    public static final String CURRENT_ADMIN_USER_ATTRIBUTE = "currentAdminUser";

    private final AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;
    private final boolean postOnly;

    private AdminAuthenticationInterceptor(AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase, boolean postOnly) {
        this.authenticateAdminJwtUseCase = authenticateAdminJwtUseCase;
        this.postOnly = postOnly;
    }

    // 경로 판정은 등록 패턴(디코딩·;matrix 제거 후 매칭)에만 맡긴다. 원본 URI 문자열 비교는 %70rojects·;x=1 변형으로 우회된다.
    public static AdminAuthenticationInterceptor forAllRequests(AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase) {
        return new AdminAuthenticationInterceptor(authenticateAdminJwtUseCase, false);
    }

    public static AdminAuthenticationInterceptor forPostRequests(AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase) {
        return new AdminAuthenticationInterceptor(authenticateAdminJwtUseCase, true);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (postOnly && !"POST".equals(request.getMethod())) {
            return true;
        }

        var authenticatedUser = authenticateAdminJwtUseCase.authenticate(request.getHeader("Authorization"));
        if (authenticatedUser == null) {
            throw new AdminAuthenticationException("admin token is invalid");
        }
        request.setAttribute(CURRENT_ADMIN_USER_ATTRIBUTE, authenticatedUser);
        return true;
    }
}

package com.jake.realtimeapi.auth.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.auth.domain.exception.AdminAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class AdminAuthenticationInterceptor implements HandlerInterceptor {

    public static final String CURRENT_ADMIN_USER_ATTRIBUTE = "currentAdminUser";

    private final AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;
    private final boolean authenticateAllRequests;

    public AdminAuthenticationInterceptor(AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase) {
        this(authenticateAdminJwtUseCase, false);
    }

    private AdminAuthenticationInterceptor(AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase, boolean authenticateAllRequests) {
        this.authenticateAdminJwtUseCase = authenticateAdminJwtUseCase;
        this.authenticateAllRequests = authenticateAllRequests;
    }

    /**
     * 등록된 경로 패턴에 걸린 요청은 메서드·URI 문자열과 무관하게 모두 인증한다.
     * 원본 URI 비교는 인코딩(%69nternal)이나 ;matrix 변형으로 우회될 수 있어 /internal/**에는 쓰지 않는다.
     */
    public static AdminAuthenticationInterceptor forAllRequests(AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase) {
        return new AdminAuthenticationInterceptor(authenticateAdminJwtUseCase, true);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!authenticateAllRequests && !requiresAuthentication(request)) {
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

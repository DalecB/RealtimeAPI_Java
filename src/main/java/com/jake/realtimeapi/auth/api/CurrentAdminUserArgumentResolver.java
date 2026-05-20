package com.jake.realtimeapi.auth.api;

import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentAdminUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAdminUser.class)
                && parameter.getParameterType().equals(AuthenticatedAdminUser.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        return webRequest.getAttribute(AdminAuthenticationInterceptor.CURRENT_ADMIN_USER_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
    }
}

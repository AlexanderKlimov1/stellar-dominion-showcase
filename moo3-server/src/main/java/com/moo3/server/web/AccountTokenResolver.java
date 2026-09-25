package com.moo3.server.web;

import com.moo3.server.web.error.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Достаёт пропуск учётной записи из заголовка {@code X-Account-Token} — п. 3.1.
 * <p>
 * Только заголовок, без запасного параметра запроса: в отличие от пропуска игрока, этот
 * не нужен подписке на события, а адреса оседают в журналах и истории браузера.
 */
@Component
public class AccountTokenResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-Account-Token";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AccountToken.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer container,
                                  NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
        String token = servletRequest == null ? null : servletRequest.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            throw new ForbiddenException("auth.accountHeaderRequired", HEADER);
        }
        return token;
    }
}

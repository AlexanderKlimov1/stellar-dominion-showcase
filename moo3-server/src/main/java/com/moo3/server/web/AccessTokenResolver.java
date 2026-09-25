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
 * Достаёт пропуск игрока из заголовка {@code X-Access-Token}, а если его нет — из
 * параметра запроса.
 * <p>
 * Заголовок появился, чтобы пропуск перестал ходить в адресе: адреса пишутся в журналы
 * доступа, кэшируются прокси и остаются в истории браузера. Параметр поддержан и дальше —
 * подписка на события открывается через {@code EventSource}, а он заголовков не умеет.
 */
@Component
public class AccessTokenResolver implements HandlerMethodArgumentResolver {

    /** Имя заголовка с пропуском игрока. */
    public static final String HEADER = "X-Access-Token";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AccessToken.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer container,
                                  NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
        if (servletRequest == null) {
            throw new ForbiddenException("auth.playerTokenMissing");
        }

        String header = servletRequest.getHeader(HEADER);
        String token = header != null && !header.isBlank() ? header : servletRequest.getParameter("accessToken");
        if (token == null || token.isBlank()) {
            throw new ForbiddenException("auth.playerTokenRequired", HEADER);
        }
        return token;
    }
}

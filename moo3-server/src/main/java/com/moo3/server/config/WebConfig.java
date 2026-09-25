package com.moo3.server.config;

import com.moo3.server.web.AccessTokenResolver;
import com.moo3.server.web.AccountTokenResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;
    private final AccessTokenResolver accessTokenResolver;
    private final AccountTokenResolver accountTokenResolver;

    public WebConfig(CorsProperties corsProperties,
                     AccessTokenResolver accessTokenResolver,
                     AccountTokenResolver accountTokenResolver) {
        this.corsProperties = corsProperties;
        this.accessTokenResolver = accessTokenResolver;
        this.accountTokenResolver = accountTokenResolver;
    }

    /**
     * Язык ответа — п. 3.5 (локализация): по заголовку {@code Accept-Language}, и только
     * из двух поддерживаемых. Всё остальное — английский: он язык игры по умолчанию, а
     * русский игрок выбирает в клиенте, и клиент шлёт заголовок с каждым запросом.
     * <p>
     * Бин назван {@code localeResolver} нарочно: под этим именем Spring Boot ставит свой,
     * и своим он уступает место. Язык вне запроса ставит {@code Messages}.
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.forLanguageTag("ru")));
        return resolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(corsProperties.allowedOrigins().split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // Заголовок с пропуском игрока браузер шлёт из другого источника только
                // при явном разрешении, а с ним же уходит и Content-Type.
                .allowedHeaders("*");
    }

    /**
     * Пропуск игрока приходит заголовком или параметром — см. {@link AccessTokenResolver};
     * пропуск учётной записи — только заголовком, см. {@link AccountTokenResolver} (п. 3.1).
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(accessTokenResolver);
        resolvers.add(accountTokenResolver);
    }
}

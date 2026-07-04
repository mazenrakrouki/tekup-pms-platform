package com.pms.shared.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enregistre l'intercepteur de scope projet (ADR-021) sur toutes les ressources
 * rattachées à un projet. N'utilise pas {@code @EnableWebMvc} pour préserver
 * l'auto-configuration MVC de Spring Boot.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ProjectScopeInterceptor projectScopeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(projectScopeInterceptor)
                .addPathPatterns("/api/projects/**");
    }
}

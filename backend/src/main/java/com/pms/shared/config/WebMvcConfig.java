package com.pms.shared.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Registers ProjectScopeInterceptor on every /api/projects/** URL. Being a @Component isn't
// enough for an interceptor to run - without this registration ADR-021 scoping is silently
// not enforced and a manager could read a project's budget/margins without being on it.

/**
 * Registers the project-scope interceptor (ADR-021). Implements {@code WebMvcConfigurer}
 * only (no {@code @EnableWebMvc}) so Spring Boot's MVC auto-configuration stays intact.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    // Injected (not "new") because it needs ProjectScopeService, or it'd NPE on first request.
    private final ProjectScopeInterceptor projectScopeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(projectScopeInterceptor)
                // Wider than what the interceptor acts on: it also lets through URLs with no
                // project number, which the interceptor recognizes and ignores.
                .addPathPatterns("/api/projects/**");
    }
}

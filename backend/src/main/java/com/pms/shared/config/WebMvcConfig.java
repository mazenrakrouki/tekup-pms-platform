package com.pms.shared.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// =============================================================================
// FILE: WebMvcConfig.java
//
// WHAT THIS FILE IS
//   Four lines of wiring: they tell Spring MVC to run ProjectScopeInterceptor
//   on every URL that starts with /api/projects. It decides nothing itself.
//
// WHERE IT SITS IN THE FLOW
//   Spring Boot reads this class at start-up.
//     ProjectScopeInterceptor (same package) is the object being registered;
//     ProjectScopeService is what that interceptor calls;
//     SecurityConfig (same package) has already identified the caller by then.
//   At run time nothing calls this file: it only speaks once, while the
//   application starts.
//
// WHY IT EXISTS - what would break if you deleted it
//   ProjectScopeInterceptor is a @Component, so Spring would still build it -
//   and never call it. Being a bean is not enough for an interceptor; it has to
//   be registered here. Delete this file and ADR-021 stops being enforced with
//   no error, no warning and no failing test that says so. A project manager
//   could then open /api/projects/9/devis-interne, a project he does not
//   manage, and read its budget and its margins. This is the whole reason the
//   file deserves a comment longer than its own code.
// =============================================================================

/**
 * Registers the project-scope interceptor (ADR-021) on every resource attached
 * to a project. It does NOT use {@code @EnableWebMvc}, so that the MVC
 * auto-configuration of Spring Boot is preserved.
 *
 * <p>That second sentence is the real decision of this file.
 * {@code @EnableWebMvc} means "I take full control of Spring MVC", and Boot
 * then steps back: the JSON conversion, the date format, the static resource
 * handling and the error handling all have to be declared by hand. Implementing
 * {@code WebMvcConfigurer} alone, as here, only ADDS to what Boot already set
 * up. With {@code @EnableWebMvc} on this class, the application would still
 * start and the first response would come back with the wrong JSON settings -
 * dates as raw numbers instead of ISO text, for instance - which is a painful
 * bug to trace back to one annotation.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    // The interceptor to install. It is injected, not created with "new",
    // because it needs ProjectScopeService inside it; building it here by hand
    // would give an object with an empty dependency and a
    // NullPointerException on the first project request.
    private final ProjectScopeInterceptor projectScopeInterceptor;

    /**
     * Called once by Spring MVC at start-up to collect the interceptors.
     * It gives nothing back; it writes into the registry it receives.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(projectScopeInterceptor)
                // "/api/projects/**" covers /api/projects and everything under
                // it. The pattern is deliberately wider than what the
                // interceptor itself acts on: it also lets through the URLs
                // with no project number, which the interceptor recognises and
                // ignores. Wider here, precise there - the opposite order would
                // mean editing this file every time a new sub-resource is
                // added under a project.
                // Without any pattern at all the interceptor would run on every
                // URL of the application, including /api/auth/login, for no
                // reason.
                .addPathPatterns("/api/projects/**");
    }
}

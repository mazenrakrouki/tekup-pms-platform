package com.pms.shared.config;

import com.pms.auth.security.FirstLoginFilter;
import com.pms.auth.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// Defines HTTP security for the whole backend: open vs authenticated URLs, security headers,
// and the order of the JWT and first-login filters. Without it Spring Boot falls back to its
// own default security (console-generated password, HTML login form, sessions) and
// @PreAuthorize never gets a permission list to check.
//
// This file only answers "is somebody logged in?" - never "may they do this?" (that's
// @PreAuthorize on services, ADR-001) or "on which project?" (ProjectScopeInterceptor,
// ADR-021). No rule below names a role: roles/permissions are DB rows an admin edits at runtime.

@Configuration
@EnableWebSecurity
// Activates @PreAuthorize on service methods. Forgetting this makes every
// @PreAuthorize("hasAuthority(...)") a no-op with no error or log - the annotation stays
// visible but stops protecting anything.
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    // H-2: while firstLogin=true, blocks every endpoint except change-password, logout, refresh.
    private final FirstLoginFilter firstLoginFilter;

    // 2^12 bcrypt rounds by default. A property, not a constant, so cost can be tuned per
    // environment without a code change; existing hashes keep working since bcrypt embeds its
    // own cost in the stored text.
    @Value("${pms.security.bcrypt-strength:12}")
    private int bcryptStrength;

    // Dev uses localhost:4200; prod passes CORS_ALLOWED_ORIGINS. Kept as a property because the
    // containerized stack (nginx same-origin proxy) uses a different list than local dev.
    @Value("${pms.cors.allowed-origins:http://localhost:4200}")
    private List<String> corsAllowedOrigins;

    /**
     * Builds the filter chain every HTTP request goes through. One chained call because
     * that's how the Spring Security 6 API is shaped. {@code throws Exception} is left
     * uncaught on purpose: a chain that can't build must stop the app at start-up, not boot
     * with half a security configuration.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Off: the caller is identified by the Authorization header, which a browser
            // never attaches on its own, so CSRF doesn't apply. The refresh cookie is
            // protected separately (path-scoped, SameSite=Strict in AuthController).
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .headers(h -> h
                // Blocks clickjacking (embedding this app in an invisible iframe).
                .frameOptions(fo -> fo.deny())
                // nosniff: stops the browser from guessing content type from bytes.
                .contentTypeOptions(cto -> {})
                // No source URL leaks to external sites when a user follows an outbound link.
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            )
            // No HttpSession: identity travels in the JWT, and a session would mean a
            // JSESSIONID cookie - reintroducing the CSRF risk just disabled above.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // First match wins, so open doors are listed before the catch-all.
            .authorizeHttpRequests(auth -> auth
                // Listed individually, not /api/auth/**, because change-password sits under
                // the same prefix and must stay behind authentication.
                .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                // Swagger UI must load before anyone can sign in from it; only exposes route
                // shapes, calling a route still needs a token.
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Docker health checks; show-details:never keeps the body to {"status":"UP"}.
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                // Closed by default: a controller added tomorrow is protected the day it's
                // written instead of leaking until someone remembers to list it.
                .anyRequest().authenticated()
            )
            // Plain 401 instead of a login-page redirect, since the client is Angular: its
            // interceptor watches for 401 to trigger a silent token refresh.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Non authentifié"))
            )
            // Before the built-in form-login filter, so the caller is known by the time the
            // rules above are evaluated.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // After the JWT filter: it needs the caller that filter installs (H-2).
            .addFilterAfter(firstLoginFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Prevents FirstLoginFilter from being double-registered: as a {@code @Component} it would
     * also be auto-installed by Boot in the plain servlet chain (before security context is
     * filled), so this publishes that registration disabled, keeping it only in the explicit
     * spot after the JWT filter in {@link #filterChain}.
     */
    @Bean
    public FilterRegistrationBean<FirstLoginFilter> firstLoginFilterRegistration(FirstLoginFilter filter) {
        FilterRegistrationBean<FirstLoginFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false);
        return bean;
    }

    /**
     * Hashes and verifies passwords, injected into AuthService, UserCrudService,
     * DataInitializer. bcrypt is deliberately slow and salts each hash, so stolen hashes are
     * expensive to brute-force and identical passwords don't produce identical hashes. Returns
     * the {@code PasswordEncoder} interface, not the impl, so swapping the algorithm is one line.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    /**
     * Builds the CORS answer the browser receives. CORS is enforced by the browser, not this
     * server - it protects a logged-in user from an unexpected page, not a wall against
     * curl/Postman; the real wall is the authentication rules in {@link #filterChain}.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        // Must be an exact list: setAllowCredentials(true) below forbids the "*" wildcard.
        cfg.setAllowedOrigins(corsAllowedOrigins);
        // OPTIONS is required: the browser preflights any non-GET request with it.
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        // Lets the browser send/receive the HttpOnly refresh cookie cross-origin (needed for
        // POST /api/auth/refresh in dev, :4200 to :8090); without it users get logged out
        // whenever the access token expires.
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // /actuator/health is left out: only called by Docker health checks, never a web page.
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}

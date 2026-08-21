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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final FirstLoginFilter firstLoginFilter;

    @Value("${pms.security.bcrypt-strength:12}")
    private int bcryptStrength;

    @Value("${pms.cors.allowed-origins:http://localhost:4200}")
    private List<String> corsAllowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .headers(h -> h
                .frameOptions(fo -> fo.deny())
                .contentTypeOptions(cto -> {})
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            )
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Sondes de conteneur (ADR-026). Sans cette règle, /actuator/health
                // renvoie 401 : le healthcheck Docker ne passe jamais au vert et
                // `depends_on: service_healthy` bloque indéfiniment.
                // show-details: never (application.yml) → aucune fuite d'information.
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Non authentifié"))
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(firstLoginFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /** Prevent FirstLoginFilter from being double-registered as a servlet filter. */
    @Bean
    public FilterRegistrationBean<FirstLoginFilter> firstLoginFilterRegistration(FirstLoginFilter filter) {
        FilterRegistrationBean<FirstLoginFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false);
        return bean;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(corsAllowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}

package com.pms.auth.security;

import com.pms.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

// Keeps a brand-new account shut until its owner changes the administrator-generated password.
// Runs right after JwtAuthenticationFilter (SecurityConfig.addFilterAfter). H-2: this used to be
// enforced only in Angular, which a curl/Postman call could bypass entirely; moving it here
// closes that hole. Also registered as a plain servlet Filter by Spring Boot's component scan —
// SecurityConfig disables that second registration (setEnabled(false)) since it would run
// outside the security chain, before the security context is filled.

/**
 * H-2: Server-side enforcement of first-login password change.
 * If the authenticated user's JWT carries firstLogin=true, only the
 * change-password, logout and refresh endpoints are reachable; everything else
 * returns 403 with the code FIRST_LOGIN_REQUIRED.
 */
@Component
@RequiredArgsConstructor
public class FirstLoginFilter extends OncePerRequestFilter {

    // The three URLs reachable while firstLogin is true: change-password (the only way to clear
    // the flag), logout (leaving without changing anything), and refresh (so a slow user isn't
    // locked out by an expired access token before he finishes the form).
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/auth/change-password",
            "/api/auth/logout",
            "/api/auth/refresh"
    );

    private final JwtService jwtService;

    /**
     * Lets the request through, or stops it with 403 FIRST_LOGIN_REQUIRED when the caller still
     * owes a password change.
     *
     * <p>Reads the claim from the signed token rather than the Authentication object (which only
     * carries e-mail and authorities) — a value the browser could edit, a signed claim can't.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Unauthenticated requests and the three allowed paths pass straight through.
        if (auth == null || !auth.isAuthenticated() || ALLOWED_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        if (token != null && jwtService.extractFirstLogin(token)) {
            // 403, not 401: the person IS identified, just not yet allowed anywhere. A 401
            // would make the Angular interceptor fire /refresh and loop forever on the same flag.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            // Written by hand because this runs before Spring MVC, so GlobalExceptionHandler
            // never sees this case. FIRST_LOGIN_REQUIRED is a contract with auth.interceptor.ts.
            response.getWriter().write(
                    "{\"code\":\"FIRST_LOGIN_REQUIRED\"," +
                    "\"message\":\"Veuillez changer votre mot de passe avant de continuer.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Pulls the raw token out of "Authorization: Bearer &lt;token&gt;", or null if absent/malformed.
     * JwtAuthenticationFilter holds the same six lines, read independently.
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

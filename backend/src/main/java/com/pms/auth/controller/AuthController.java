package com.pms.auth.controller;

import com.pms.auth.dto.AuthResponse;
import com.pms.auth.dto.ChangePasswordRequest;
import com.pms.auth.dto.LoginRequest;
import com.pms.auth.dto.TokenBundle;
import com.pms.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Authentification", description = "Login, refresh token, logout, changement de mot de passe")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    public static final String REFRESH_COOKIE = "pms_refresh";

    private final AuthService authService;

    @Value("${pms.security.cookie-secure:false}")
    private boolean cookieSecure;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        TokenBundle bundle = authService.login(request);
        addRefreshCookie(response, bundle.refreshToken(), bundle.refreshMaxAgeSeconds());
        return ResponseEntity.ok(bundle.body());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        TokenBundle bundle = authService.refresh(refreshToken);
        addRefreshCookie(response, bundle.refreshToken(), bundle.refreshMaxAgeSeconds());
        return ResponseEntity.ok(bundle.body());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal String email,
            HttpServletResponse response) {
        authService.logout(email);
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(email, request);
        return ResponseEntity.noContent().build();
    }

    private void addRefreshCookie(HttpServletResponse response, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/api/auth/refresh");
        // La duree vient du service, qui l'a deja appliquee au JWT : les deux ne peuvent
        // pas diverger et laisser un cookie survivre a son contenu.
        cookie.setMaxAge(maxAgeSeconds);
        // SameSite=Strict via header (Servlet Cookie API doesn't support SameSite directly)
        response.addCookie(cookie);
        // Override with SameSite attribute
        String header = String.format(
                "%s=%s; Path=/api/auth/refresh; Max-Age=%d; HttpOnly%s; SameSite=Strict",
                REFRESH_COOKIE, value, maxAgeSeconds,
                cookieSecure ? "; Secure" : "");
        response.setHeader("Set-Cookie", header);
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        String header = String.format(
                "%s=; Path=/api/auth/refresh; Max-Age=0; HttpOnly%s; SameSite=Strict",
                REFRESH_COOKIE,
                cookieSecure ? "; Secure" : "");
        response.setHeader("Set-Cookie", header);
    }
}

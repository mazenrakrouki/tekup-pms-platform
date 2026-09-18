package com.pms.auth.service;

import com.pms.auth.dto.AuthResponse;
import com.pms.auth.dto.TokenBundle;
import com.pms.auth.dto.ChangePasswordRequest;
import com.pms.auth.dto.LoginRequest;
import com.pms.shared.exception.TooManyRequestsException;
import com.pms.user.entity.Permission;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final CacheManager cacheManager;
    private final LoginAttemptTracker loginAttemptTracker;

    // H-2: precomputed dummy hash for constant-time comparison when email is unknown
    private String dummyHash;

    @PostConstruct
    void init() {
        dummyHash = passwordEncoder.encode("dummy-timing-equalization");
    }

    /**
     * H-2: rate-limited login with timing equalization.
     * Always runs bcrypt even when the email is not found, to prevent
     * user enumeration via response-time differences.
     */
    @Transactional
    public TokenBundle login(LoginRequest request) {
        if (loginAttemptTracker.isBlocked(request.email())) {
            throw new TooManyRequestsException(
                    "Trop de tentatives de connexion. Réessayez dans " +
                    (LoginAttemptTracker.WINDOW_SECONDS / 60) + " minutes.");
        }

        // Look up the user — may return empty
        User user = userRepository.findActiveByEmailWithRole(request.email()).orElse(null);

        // Always run bcrypt to equalize timing (prevents user enumeration)
        String hashToVerify = (user != null) ? user.getPasswordHash() : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToVerify);

        if (user == null || !passwordMatches) {
            loginAttemptTracker.recordFailure(request.email());
            throw new BadCredentialsException("Identifiants incorrects");
        }

        if (!user.isActive()) {
            loginAttemptTracker.recordFailure(request.email());
            throw new DisabledException("Compte désactivé");
        }

        loginAttemptTracker.reset(request.email());
        return buildBundle(user, request.rememberMe());
    }

    /**
     * H-1: Rotation — each refresh token is single-use.
     * After a successful refresh, tokenVersion is bumped; the old refresh token
     * (which carries the old tokenVersion) is therefore invalidated on next use.
     * If the presented token already has a stale version, the existing version check
     * catches it and returns 401 ("Token révoqué") — that is our reuse detection.
     */
    @Transactional
    public TokenBundle refresh(String refreshTokenValue) {
        if (!jwtService.isTokenValid(refreshTokenValue)) {
            throw new JwtException("Refresh token invalide ou expiré");
        }

        if (!"refresh".equals(jwtService.extractType(refreshTokenValue))) {
            throw new JwtException("Type de token incorrect");
        }

        boolean rememberMe = jwtService.extractRememberMe(refreshTokenValue);

        String email = jwtService.extractEmail(refreshTokenValue);
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        int presented = jwtService.extractTokenVersion(refreshTokenValue);
        if (presented != user.getTokenVersion()) {
            // Stale version → token was already rotated or explicitly revoked.
            // Could indicate refresh-token theft — log prominently.
            log.warn("Refresh token replay detected for {} (presented={}, current={}). Possible token theft.",
                    email, presented, user.getTokenVersion());
            throw new JwtException("Token révoqué");
        }

        // Rotation: bump tokenVersion so the current refresh token can never be reused
        int oldVersion = user.getTokenVersion();
        user.revokeAllTokens();
        User saved = userRepository.save(user);

        // Evict the cache entry for the old access token
        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + oldVersion);
        }

        // La rotation conserve la portee de la session : un utilisateur ayant coche
        // "se souvenir de moi" ne doit pas etre deconnecte au bout de la duree courte
        // simplement parce que son jeton a ete renouvele entre-temps.
        return buildBundle(saved, rememberMe);
    }

    @Transactional
    public void logout(String email) {
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        int oldVersion = user.getTokenVersion();
        user.revokeAllTokens();
        userRepository.save(user);

        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + oldVersion);
        }

        log.info("Session révoquée pour : {}", email);
    }

    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Mot de passe actuel incorrect");
        }

        int oldVersion = user.getTokenVersion();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFirstLogin(false);
        user.revokeAllTokens();
        userRepository.save(user);

        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + oldVersion);
        }

        log.info("Mot de passe changé pour : {}", email);
    }

    private TokenBundle buildBundle(User user, boolean rememberMe) {
        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user, rememberMe);
        Set<String> permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());
        AuthResponse body = new AuthResponse(
                user.getId(),
                accessToken,
                user.isFirstLogin(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().getName(),
                permissions
        );
        return new TokenBundle(body, refreshToken, jwtService.refreshMaxAge(rememberMe));
    }
}

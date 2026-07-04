package com.pms.auth.service;

import com.pms.auth.dto.AuthResponse;
import java.util.Set;
import java.util.stream.Collectors;
import com.pms.auth.dto.ChangePasswordRequest;
import com.pms.auth.dto.LoginRequest;
import com.pms.auth.dto.RefreshRequest;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final CacheManager cacheManager;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findActiveByEmailWithRole(request.email())
                .orElseThrow(() -> new BadCredentialsException("Identifiants incorrects"));

        if (!user.isActive()) {
            throw new DisabledException("Compte désactivé");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Identifiants incorrects");
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String token = request.refreshToken();

        if (!jwtService.isTokenValid(token)) {
            throw new JwtException("Refresh token invalide ou expiré");
        }

        if (!"refresh".equals(jwtService.extractType(token))) {
            throw new JwtException("Type de token incorrect");
        }

        String email = jwtService.extractEmail(token);
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        // Vérification de la version du token (ADR-017)
        int tokenVersion = jwtService.extractTokenVersion(token);
        if (tokenVersion != user.getTokenVersion()) {
            throw new JwtException("Token révoqué");
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public void logout(String email) {
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        int oldVersion = user.getTokenVersion();
        user.revokeAllTokens();
        userRepository.save(user);

        // Éviction de l'entrée de cache correspondant à la version révoquée (ADR-017)
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

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        Set<String> permissions = user.getRole().getPermissions().stream()
                .map(p -> p.getCode())
                .collect(Collectors.toSet());
        return new AuthResponse(
                user.getId(),
                accessToken, refreshToken, user.isFirstLogin(),
                user.getEmail(), user.getFullName(), user.getRole().getName(), permissions
        );
    }
}

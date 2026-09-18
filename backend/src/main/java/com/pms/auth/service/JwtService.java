package com.pms.auth.service;

import com.pms.user.entity.Permission;
import com.pms.user.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
@Slf4j
public class JwtService {

    @Value("${pms.jwt.secret}")
    private String jwtSecret;

    @Value("${pms.jwt.access-token-expiration}")
    private long accessTokenExpirySeconds;

    @Value("${pms.jwt.refresh-token-expiration}")
    private long refreshTokenExpirySeconds;

    /** Duree d'une session "se souvenir de moi". Doit correspondre au libelle affiche. */
    @Value("${pms.jwt.remember-me-expiration:2592000}")
    private long rememberMeExpirySeconds;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        List<String> permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .toList();

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("role", user.getRole().getName())
                .claim("permissions", permissions)
                .claim("tokenVersion", user.getTokenVersion())
                .claim("type", "access")
                .claim("firstLogin", user.isFirstLogin())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(accessTokenExpirySeconds)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Jeton de rafraichissement.
     *
     * <p>Le drapeau {@code rememberMe} est inscrit dans le jeton lui-meme. C'est ce qui
     * permet a la rotation de le preserver : sans cette revendication, le jeton emis au
     * premier rafraichissement retomberait a la duree courte et la session longue
     * disparaitrait sans que l'utilisateur comprenne pourquoi.
     */
    public String generateRefreshToken(User user, boolean rememberMe) {
        long ttl = rememberMe ? rememberMeExpirySeconds : refreshTokenExpirySeconds;
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("tokenVersion", user.getTokenVersion())
                .claim("type", "refresh")
                .claim("rememberMe", rememberMe)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(ttl)))
                .signWith(signingKey)
                .compact();
    }

    /** Duree, en secondes, a appliquer au cookie pour que celui-ci n'excede pas le jeton. */
    public int refreshMaxAge(boolean rememberMe) {
        return (int) (rememberMe ? rememberMeExpirySeconds : refreshTokenExpirySeconds);
    }

    /** Relit le drapeau porte par un jeton de rafraichissement (absent = false). */
    public boolean extractRememberMe(String token) {
        Boolean flag = validateAndParse(token).get("rememberMe", Boolean.class);
        return Boolean.TRUE.equals(flag);
    }

    public Claims validateAndParse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractEmail(String token) {
        return validateAndParse(token).getSubject();
    }

    public int extractTokenVersion(String token) {
        return validateAndParse(token).get("tokenVersion", Integer.class);
    }

    public String extractType(String token) {
        return validateAndParse(token).get("type", String.class);
    }

    /** Returns true if the token carries firstLogin=true (user must change password). */
    public boolean extractFirstLogin(String token) {
        Boolean val = validateAndParse(token).get("firstLogin", Boolean.class);
        return Boolean.TRUE.equals(val);
    }

    public boolean isTokenValid(String token) {
        try {
            validateAndParse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT invalide : {}", e.getMessage());
            return false;
        }
    }
}

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
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(accessTokenExpirySeconds)))
                .signWith(signingKey)
                .compact();
    }

    public String generateRefreshToken(User user) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("tokenVersion", user.getTokenVersion())
                .claim("type", "refresh")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(refreshTokenExpirySeconds)))
                .signWith(signingKey)
                .compact();
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

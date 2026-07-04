package com.pms.auth.security;

import com.pms.auth.service.JwtService;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final CacheManager cacheManager;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null && jwtService.isTokenValid(token)) {
            try {
                String type = jwtService.extractType(token);
                if ("access".equals(type)) {
                    authenticate(token);
                }
            } catch (Exception e) {
                log.debug("Échec d'authentification JWT : {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        String email = jwtService.extractEmail(token);
        int tokenVersion = jwtService.extractTokenVersion(token);
        String cacheKey = email + ":" + tokenVersion;

        // Clé = email:version — un token révoqué (ancienne version) ne peut pas
        // bénéficier du cache d'une nouvelle session (ADR-017)
        Cache cache = cacheManager.getCache("securityContext");
        UsernamePasswordAuthenticationToken auth = cache != null
                ? cache.get(cacheKey, UsernamePasswordAuthenticationToken.class)
                : null;

        if (auth == null) {
            User user = userRepository.findActiveByEmailWithRole(email).orElse(null);
            if (user == null || user.getTokenVersion() != tokenVersion) {
                return;
            }

            List<SimpleGrantedAuthority> authorities = user.getRole().getPermissions().stream()
                    .map(p -> new SimpleGrantedAuthority(p.getCode()))
                    .toList();

            auth = new UsernamePasswordAuthenticationToken(email, null, authorities);

            if (cache != null) {
                cache.put(cacheKey, auth);
            }
        }

        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

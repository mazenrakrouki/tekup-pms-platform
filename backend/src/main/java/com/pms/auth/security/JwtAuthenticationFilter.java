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

// Turns "Authorization: Bearer <jwt>" into the logged-in caller of the current request — the
// only place that builds an Authentication for an API call. Placed with
// addFilterBefore(..., UsernamePasswordAuthenticationFilter.class) in SecurityConfig, right
// before FirstLoginFilter.
//
// ADR-017: permissions are NOT read from the token — only identity is. They're read from the
// database (or the 5-minute "securityContext" cache built from it), so a session can be cut
// before its token expires by bumping users.token_version, and a role's permissions change take
// effect on the very next request instead of waiting for expiry. This filter never refuses a
// request itself: a bad token just leaves it anonymous, and the 401/403 is decided later by
// Spring Security or @PreAuthorize.

/**
 * Reads the bearer token of each request and, when it is a valid ACCESS token, puts the matching
 * user and his permissions into the Spring Security context for the rest of that request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    // findActiveByEmailWithRole loads the user, role and permissions in one JOIN FETCH query —
    // a filter runs outside any transaction, so a lazy permission list would throw
    // LazyInitializationException the moment it's walked.
    private final UserRepository userRepository;
    // Handle on the Caffeine caches; only "securityContext" is used here.
    private final CacheManager cacheManager;

    /**
     * Runs on every request. Fills the security context for a valid access token, then always
     * continues the chain — stopping here would also block the permitAll login/refresh endpoints.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null && jwtService.isTokenValid(token)) {
            try {
                // "type" tells an access token from a refresh token apart — both share the same
                // signature. Without this check the long-lived refresh token could be pasted
                // into the Authorization header and used as an access token for days, and it
                // carries no "firstLogin" claim, so FirstLoginFilter's H-2 gate would be skipped.
                String type = jwtService.extractType(token);
                if ("access".equals(type)) {
                    authenticate(token);
                }
            } catch (Exception e) {
                // A stale/malformed claim (e.g. a token from an older app version) ends up
                // anonymous -> clean 401, instead of a 500. Message only, never the token itself.
                log.debug("Échec d'authentification JWT : {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Builds — or takes back from the cache — the Authentication of the user named by the
     * token, and installs it for the rest of the request.
     *
     * <p>Returns in silence when the account no longer exists or the token was revoked, so the
     * request just falls back to anonymous/401 instead of leaking via a 500 whether the e-mail exists.
     */
    private void authenticate(String token) {
        String email = jwtService.extractEmail(token);
        int tokenVersion = jwtService.extractTokenVersion(token);
        // Key is email:version, not email alone, so a revoked token (old version) can never
        // reuse the cache entry of a newer session (ADR-017).
        String cacheKey = email + ":" + tokenVersion;

        Cache cache = cacheManager.getCache("securityContext");
        UsernamePasswordAuthenticationToken auth = cache != null
                ? cache.get(cacheKey, UsernamePasswordAuthenticationToken.class)
                : null;

        if (auth == null) {
            User user = userRepository.findActiveByEmailWithRole(email).orElse(null);
            // Revocation check (ADR-017): null user (soft-deleted) or a tokenVersion mismatch
            // (logout, password change, deactivation, role change all bump it) both mean the
            // token is stale, even if its signature and expiry are still fine.
            if (user == null || user.getTokenVersion() != tokenVersion) {
                return;
            }

            // Permission codes -> Spring authorities, never a role name (dynamic RBAC rule).
            // Read from the database row, not the token's "permissions" claim, which exists only
            // for the Angular UI — trusting it would delay revocation until token expiry.
            List<SimpleGrantedAuthority> authorities = user.getRole().getPermissions().stream()
                    .map(p -> new SimpleGrantedAuthority(p.getCode()))
                    .toList();

            // Three-arg constructor marks the object as already authenticated. Principal is the
            // e-mail; credentials stay null, the password has no business being in memory here.
            auth = new UsernamePasswordAuthenticationToken(email, null, authorities);

            // Cached 5 minutes (Caffeine expireAfterWrite) so most requests skip the DB round
            // trip. Stays safe because AuthService/UserCrudService evict "email:oldVersion" on
            // any security event, and RoleAdminService clears the whole cache on a role edit.
            if (cache != null) {
                cache.put(cacheKey, auth);
            }
        }

        // No HTTP session backs this — SecurityConfig is STATELESS — so this ThreadLocal
        // context is what makes @PreAuthorize work further down the call.
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Pulls the raw token out of "Authorization: Bearer &lt;token&gt;", or null if absent/malformed.
     * FirstLoginFilter holds the same six lines, read independently.
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

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

// =============================================================================
// FILE: JwtAuthenticationFilter.java
//
// WHAT THIS FILE IS
//   The filter that turns the header "Authorization: Bearer <jwt>" into the
//   logged-in caller of the current request. A filter is a small piece of code
//   that every HTTP request crosses before it reaches a controller. This is the
//   only place in the backend that builds an Authentication object for an API
//   call, so it is the only place that answers the question "who is asking?".
//   A JWT (JSON Web Token) is a short text signed by the server: anybody can
//   read what is written inside it, nobody can change it without the secret key.
//
// WHERE IT SITS IN THE FLOW
//   The Angular auth.interceptor adds the header
//     -> Spring Security filter chain
//        -> JwtAuthenticationFilter (THIS FILE). SecurityConfig places it with
//           addFilterBefore(..., UsernamePasswordAuthenticationFilter.class).
//        -> FirstLoginFilter (same folder, audit item H-2), which needs the
//           caller this filter has just put in place.
//        -> Spring Security's own authorization filter: the rule
//           anyRequest().authenticated() of SecurityConfig answers 401 when
//           this filter has put nobody in place.
//     -> Spring MVC
//        -> ProjectScopeInterceptor (ADR-021) for /api/projects/{id}/**
//        -> controller -> service, where @PreAuthorize("hasAuthority('X')")
//           reads the authority list built below.
//   Calls: JwtService (signature, expiry, claims), UserRepository
//   (findActiveByEmailWithRole: one single query that loads the user, his role
//   and the role's permissions together) and the Caffeine cache
//   "securityContext" declared in application.yml (maximumSize=2000,
//   expireAfterWrite=5m; caching is switched on by @EnableCaching on
//   PmsApplication).
//
// WHY IT EXISTS -- what would break if you deleted it
//   Every request would arrive anonymous, so every protected URL would answer
//   401 and nothing past the login page would work. And because the authority
//   list is built HERE, @PreAuthorize would have nothing to test: the whole
//   permission system would have no input at all.
//
// ADR-017 -- THE POINT A JURY WILL ASK ABOUT
//   The access token carries identity only. The permissions are NOT taken from
//   the token; they are read from the database, or from the five-minute cache
//   entry built from it. Two consequences:
//     * a session can be cut before its token expires, by comparing the
//       tokenVersion written in the token with the column users.token_version;
//     * a permission added to or removed from a role applies on the very next
//       request, because the services that edit the matrix drop the cache
//       entries (AuthService, UserCrudService, RoleAdminService).
//   The price of that choice is worth saying out loud: the application is not
//   100% stateless, it keeps this one small cache on the server. That is a
//   deliberate trade, not an oversight.
//
// WHAT THIS FILTER NEVER DOES
//   It never refuses a request by itself. Even a rotten token only leaves the
//   request anonymous and lets it continue; the 401 or the 403 is decided
//   later, by Spring Security or by @PreAuthorize. It also never tests a role
//   NAME -- only permission codes.
// =============================================================================

/**
 * Reads the bearer token of each request and, when it is a valid ACCESS token,
 * puts the matching user and his permissions into the Spring Security context
 * for the rest of that request.
 *
 * <p>It extends OncePerRequestFilter instead of implementing Filter directly:
 * Spring then guarantees the work below runs one single time per request.
 * Without that guarantee an internal forward -- the one towards /error, for
 * example -- would replay the whole authentication, which costs a database read
 * and can write the same failure twice in the log.
 *
 * <p>The three annotations: {@code @Component} makes it a bean so SecurityConfig
 * can inject it into the chain; {@code @RequiredArgsConstructor} (Lombok)
 * writes the constructor for the three final fields below, which is how Spring
 * hands them over; {@code @Slf4j} (Lombok) creates the {@code log} object used
 * in the catch block.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // Reads and checks the token: signature, expiry date, and the claims. A
    // claim is one named value stored inside the token, for instance "type" or
    // "tokenVersion".
    private final JwtService jwtService;
    // Used for one query only, findActiveByEmailWithRole, which loads the user
    // with his role and the role's permissions in a single statement (JOIN
    // FETCH). Why that matters here: a filter runs outside any transaction, so
    // a permission list loaded lazily would blow up with
    // LazyInitializationException when the stream below walks through it.
    private final UserRepository userRepository;
    // Spring's handle on the Caffeine caches of application.yml. Only the
    // "securityContext" cache is used here: it holds the ready-made
    // Authentication object, so the database is not read on every request.
    private final CacheManager cacheManager;

    /**
     * Runs on every single request. Reads the token and, when it is a valid
     * access token, fills the security context; then hands the request to the
     * next filter, whatever happened before.
     *
     * <p>It gives nothing back. Its only visible effect is the Authentication
     * placed in SecurityContextHolder, or the absence of one.
     *
     * <p>Why it always calls the chain instead of answering 401 itself: the
     * public endpoints go through here too. If a missing or expired token
     * stopped the request at this point, POST /api/auth/login and
     * POST /api/auth/refresh -- which are permitAll in SecurityConfig and by
     * definition arrive without a usable access token -- could never be
     * reached, and nobody could sign in any more.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // null when there is no header, or when it does not start with
        // "Bearer ". The request then simply stays anonymous.
        String token = extractToken(request);

        // Two tests, in this order. The null test avoids doing any work for a
        // request that carries nothing (login, refresh, the health probe,
        // Swagger). isTokenValid checks the signature and the expiry date and
        // answers false instead of throwing: without that, an expired token --
        // which happens to every user every few minutes -- would produce a 500
        // error page instead of the plain 401 the Angular client knows how to
        // handle by refreshing.
        if (token != null && jwtService.isTokenValid(token)) {
            try {
                // "type" separates an access token from a refresh token. Both
                // are signed with the same secret, so checking the signature
                // alone cannot tell them apart.
                String type = jwtService.extractType(token);
                // Only an access token may authenticate a call. Without this
                // test the long-lived refresh token, the one kept in the
                // HttpOnly cookie, could be pasted into the Authorization
                // header and used as a normal access token for days. Worse: a
                // refresh token carries no "firstLogin" claim, so FirstLoginFilter
                // would read false and the H-2 gate would be walked around.
                if ("access".equals(type)) {
                    authenticate(token);
                }
            } catch (Exception e) {
                // The extract* calls read claims and can throw when a claim is
                // missing or does not have the expected java type -- a token
                // signed by an older version of the application, for example.
                // Catching here keeps the request anonymous, which ends in a
                // clean 401, instead of a 500 with a stack trace.
                // log.debug and not log.warn: a stale or malformed token is
                // ordinary traffic and would flood the log file. Only the
                // message is written, never the token itself.
                log.debug("Échec d'authentification JWT : {}", e.getMessage());
                // Drops anything already placed in the per-request security
                // context, so a token that turned out to be broken half way
                // cannot leave a partial identity behind for the rest of this
                // request.
                SecurityContextHolder.clearContext();
            }
        }

        // Always continue, see the note above the method. Stopping here would
        // break every public endpoint of the application.
        filterChain.doFilter(request, response);
    }

    /**
     * Builds -- or takes back from the cache -- the Authentication of the user
     * named by the token, and installs it for the rest of the request.
     *
     * <p>It gives nothing back, and on purpose it returns in silence when the
     * account no longer exists or when the token has been revoked: the request
     * then carries on anonymous and Spring Security answers 401. Throwing an
     * exception here would give the caller a 500, and the wording of that error
     * would tell him whether the e-mail exists in the database, which is a
     * small information leak we do not want.
     */
    private void authenticate(String token) {
        // The subject of the token. In this application the e-mail IS the
        // identity of an account: users.email is unique among the rows that are
        // not soft-deleted.
        String email = jwtService.extractEmail(token);
        // The revocation counter that was copied into the token when it was
        // signed. users.token_version moves one step forward on logout, on
        // password change, on refresh (rotation), on deactivation and on role
        // change.
        int tokenVersion = jwtService.extractTokenVersion(token);
        String cacheKey = email + ":" + tokenVersion;

        // Translated from the original French comment, same meaning:
        //   key = email:version -- a revoked token (old version) cannot profit
        //   from the cache entry of a new session (ADR-017).
        // Concretely: somebody logs out, his version goes from 4 to 5. His old
        // token still says 4 and therefore looks for the key "him@s2i.tn:4",
        // which AuthService.logout() has just evicted, while the new session
        // writes "him@s2i.tn:5". The two sessions can never be mixed up. With
        // the e-mail alone as a key, the revoked token would have found the
        // fresh entry and kept working.
        Cache cache = cacheManager.getCache("securityContext");
        // Null handling, twice over. getCache returns null when no cache with
        // that name is configured -- a test that starts the context without the
        // Caffeine settings of application.yml, for instance -- and cache.get
        // returns null when nothing is stored under the key. The ternary sends
        // both cases down the same road: auth stays null and the block below
        // rebuilds it from the database, instead of a NullPointerException on
        // every single request.
        UsernamePasswordAuthenticationToken auth = cache != null
                ? cache.get(cacheKey, UsernamePasswordAuthenticationToken.class)
                : null;

        if (auth == null) {
            // Cache miss: read the account. That query filters deleted = false
            // and loads the role and its permissions in the same statement.
            User user = userRepository.findActiveByEmailWithRole(email).orElse(null);
            // THE REVOCATION CHECK (ADR-017). Two reasons to refuse:
            //   user == null -- the account was soft-deleted while one of its
            //     tokens was still alive;
            //   version mismatch -- the token was signed before the last
            //     security event.
            // Example: an administrator deactivates somebody. The signature of
            // that person's token is still perfectly valid and the token has
            // not expired, but UserCrudService.deactivate() has moved
            // token_version forward, so this line stops him on his very next
            // request. Without this comparison a stateless JWT could not be
            // withdrawn at all, and a dismissed employee would keep working
            // until his token expired (business rule BR-007).
            // Worth knowing for a question from the jury: the "active" column
            // is deliberately NOT re-read on each request. Deactivation is
            // enforced through this version bump, and a fresh login is refused
            // by AuthService.login().
            if (user == null || user.getTokenVersion() != tokenVersion) {
                return;
            }

            // From the role's permissions to Spring Security authorities. A
            // GrantedAuthority is only a string that hasAuthority() compares
            // against. The string used is the permission CODE -- MANAGE_USERS,
            // MANAGE_DI, ... -- never the name of the role: that is the dynamic
            // RBAC rule of this project, no line of code anywhere asks "is he
            // ADMIN?".
            // Note where the codes come from: the database row read just above,
            // NOT the "permissions" claim of the token. The claim exists only
            // so the Angular UI can show or hide a menu; if the server trusted
            // it, revoking a permission would take effect only when the token
            // expired (ADR-017).
            List<SimpleGrantedAuthority> authorities = user.getRole().getPermissions().stream()
                    .map(p -> new SimpleGrantedAuthority(p.getCode()))
                    .toList();

            // The three-argument constructor is the one that marks the object
            // as ALREADY authenticated. With the two-argument one the object
            // would mean "here is a login attempt still to be checked", and
            // every request would end in 401.
            // Principal = the e-mail: that is the value a controller reads back
            // through Authentication.getName(). Credentials = null on purpose,
            // the password has nothing to do here and must never sit in memory
            // after the sign-in.
            auth = new UsernamePasswordAuthenticationToken(email, null, authorities);

            // Keep the ready-made object for the next requests of the same
            // session; Caffeine holds it five minutes (expireAfterWrite=5m).
            // Without this line every API call would run the user + role +
            // permissions query again: a screen that fires ten calls would mean
            // ten database round trips instead of one.
            // It stays safe because the entry is dropped as soon as anything
            // security-related changes: AuthService (logout, refresh, password
            // change) and UserCrudService evict "email:oldVersion", and
            // RoleAdminService clears the whole cache when the permissions of a
            // role are edited.
            if (cache != null) {
                cache.put(cacheKey, auth);
            }
        }

        // Installs the identity for the rest of this request. The context lives
        // in a ThreadLocal and is thrown away when the request ends -- there is
        // no HTTP session keeping it, SecurityConfig sets the session policy to
        // STATELESS. This single line is what makes @PreAuthorize work further
        // down the call, in the services.
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Pulls the raw token out of the header "Authorization: Bearer &lt;token&gt;",
     * or gives back null when the header is missing or has another shape.
     *
     * <p>StringUtils.hasText guards against a header that is null, empty or
     * made of spaces, so the startsWith test just below can never throw a
     * NullPointerException. substring(7) then skips exactly the seven
     * characters of "Bearer " -- one character less and the token would start
     * with a space, and the signature check would fail on every request.
     *
     * <p>FirstLoginFilter, in this same folder, holds the same six lines: the
     * two filters read the same header, each one on its own.
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

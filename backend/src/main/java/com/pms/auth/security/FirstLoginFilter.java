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

// =============================================================================
// FILE: FirstLoginFilter.java
//
// WHAT THIS FILE IS
//   The gate that keeps a brand-new account shut until its owner has changed
//   the password the administrator handed him. As long as that has not
//   happened, every URL of the API answers 403 except three.
//
// WHERE IT SITS IN THE FLOW
//   The Angular auth.interceptor sends "Authorization: Bearer <jwt>"
//     -> JwtAuthenticationFilter (same folder) puts the caller in the security
//        context
//     -> FirstLoginFilter (THIS FILE), placed right after it by
//        SecurityConfig.filterChain with addFilterAfter(...)
//     -> the rest of the security chain, then Spring MVC, then
//        ProjectScopeInterceptor (ADR-021) and the controllers.
//   It calls one method only: JwtService.extractFirstLogin(token).
//   Where the flag comes from: the column users.first_login. JwtService copies
//   it into every access token it signs; AuthService.changePassword() sets it
//   to false and moves token_version forward, so the next token no longer
//   carries it; UserCrudService.resetAccount() sets it back to true when an
//   administrator resets somebody's password.
//   On the browser side, core/interceptors/auth.interceptor.ts watches for the
//   403 written below and sends the user to the change-password screen.
//
// WHY IT EXISTS -- audit item H-2
//   H-2 describes a takeover chain on new accounts. The first-login screen used
//   to be enforced by the Angular application only, and a check done in the
//   browser protects nothing: a request sent with curl or Postman walks
//   straight past it. Anyone who knew the initial password of a colleague could
//   use that account, with that password, for as long as he wanted. This filter
//   moves the rule to the server, where it cannot be skipped. Delete this file
//   and the gate becomes decoration again.
//
// THE DOUBLE-REGISTRATION TRAP (also written in SecurityConfig)
//   This class is a @Component AND a servlet Filter, so Spring Boot would also
//   register it as a plain filter on every URL, outside the security chain,
//   at a point where the security context is not filled yet. SecurityConfig
//   declares a FilterRegistrationBean with setEnabled(false) to switch that
//   second registration off.
// =============================================================================

/**
 * H-2: Server-side enforcement of first-login password change.
 * If the authenticated user's JWT carries firstLogin=true, only the
 * change-password, logout and refresh endpoints are reachable; everything else
 * returns 403 with the code FIRST_LOGIN_REQUIRED.
 *
 * <p>Runs after {@link JwtAuthenticationFilter} (added via SecurityConfig).
 * That order is not a detail: this filter reads the Authentication that the
 * other one writes. In the reverse order it would see an empty security
 * context on every request and would let everything through.
 *
 * <p>It extends OncePerRequestFilter so the check runs one single time per
 * request. Without that guarantee an internal forward -- towards /error, for
 * instance -- could run the block below a second time and try to write the 403
 * body into a response that is already committed.
 */
@Component
@RequiredArgsConstructor
public class FirstLoginFilter extends OncePerRequestFilter {

    // The three URLs that stay open while the password has not been changed.
    // WHY each one has to be here:
    //   change-password -- the only endpoint able to clear the flag. Leave it
    //     out and the account is locked for ever: the user is refused
    //     everywhere, including on the one call that would set him free.
    //   logout          -- somebody must be allowed to leave without changing
    //     anything.
    //   refresh         -- the access token lives only a few minutes. A user
    //     who takes his time on the change-password screen would otherwise see
    //     his token expire with no way to renew it, and would be thrown back to
    //     the login page with his password still unchanged.
    // Set.of builds a small fixed set that nothing can modify afterwards, and
    // contains() on it is a hash lookup, not a walk through a list -- this code
    // runs on every request of the application.
    // The comparison below is an EXACT match on the path, with no wildcard: a
    // URL has to be one of these three strings, so nothing shaped like
    // "/api/auth/logout/something" can slip through the gate.
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/auth/change-password",
            "/api/auth/logout",
            "/api/auth/refresh"
    );

    // Used for one thing only: reading the "firstLogin" claim of the token. A
    // claim is one named value stored inside the token. The constructor that
    // receives this field is written by Lombok, from @RequiredArgsConstructor
    // above.
    private final JwtService jwtService;

    /**
     * Lets the request through, or stops it with 403 FIRST_LOGIN_REQUIRED when
     * the caller still owes a password change.
     *
     * <p>It gives nothing back: it either passes the request to the next filter
     * of the chain, or writes the answer itself and stops there.
     *
     * <p>Why it reads the token again instead of asking the Authentication
     * object: JwtAuthenticationFilter stores only the e-mail and the authority
     * list in the security context, so the firstLogin information is simply not
     * there. Reading it from the signed token is also the safest source -- a
     * value sent by the browser could be edited by hand, a claim inside a JWT
     * cannot be, not without the secret key of the server.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Who is calling, exactly as JwtAuthenticationFilter has just written
        // it a moment ago in the chain.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Three situations go straight through:
        //   auth == null, or not authenticated -- there is nobody to gate. The
        //     public endpoints (login, refresh, the health probe) arrive like
        //     this, and for a protected URL Spring Security answers 401 further
        //     down the chain anyway, so blocking here would change nothing.
        //   the path is one of ALLOWED_PATHS -- see the list above; without
        //     this the user could never reach the call that frees his account.
        if (auth == null || !auth.isAuthenticated() || ALLOWED_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            // The return matters: doFilter hands the request to everything that
            // comes after, and falling through to the bottom of the method
            // would call it a second time, running the rest of the chain and
            // the controller twice.
            return;
        }

        // null when the request carries no "Bearer ..." header. There is then
        // no claim to read, so the request is let through rather than blocked
        // on a guess. In this application an authenticated request always
        // carries that header, so this is a safety net, not a normal case.
        String token = extractToken(request);
        if (token != null && jwtService.extractFirstLogin(token)) {
            // 403 and not 401: the person IS correctly identified, he is simply
            // not allowed anywhere yet. With a 401 the Angular interceptor
            // would believe the token is dead and would fire /api/auth/refresh,
            // get a fresh token that still says firstLogin=true, and turn in
            // circles.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            // The answer is written here by hand, so nothing else will set this
            // header: it says the body is JSON encoded in UTF-8, which is what
            // lets the client decode it correctly.
            response.setContentType("application/json;charset=UTF-8");
            // The body is built as a plain string for one reason: a filter runs
            // before Spring MVC, so the @RestControllerAdvice that shapes the
            // error bodies of this project never sees this case.
            // The value FIRST_LOGIN_REQUIRED is a contract with the front end:
            // auth.interceptor.ts tests err.error?.code === 'FIRST_LOGIN_REQUIRED'
            // and navigates to /change-password. Rename the string here and the
            // browser only shows a bare 403 on every screen, with no way out.
            // The \" sequences are escaped double quotes: the text really sent
            // is {"code":"FIRST_LOGIN_REQUIRED","message":"..."}.
            response.getWriter().write(
                    "{\"code\":\"FIRST_LOGIN_REQUIRED\"," +
                    "\"message\":\"Veuillez changer votre mot de passe avant de continuer.\"}");
            // Stop here, and above all do not call chain.doFilter. Without this
            // return the controller would still run, the work would be done and
            // the data would travel back with a 403 status stuck on it -- the
            // gate would hand over exactly what it is supposed to protect.
            return;
        }

        // Normal case: the password has already been changed, so the request
        // carries on to the rest of the chain.
        chain.doFilter(request, response);
    }

    /**
     * Pulls the raw token out of the header "Authorization: Bearer &lt;token&gt;",
     * or gives back null when the header is missing or has another shape.
     *
     * <p>StringUtils.hasText guards against a header that is null, empty or
     * made of spaces, so the startsWith test just below can never throw a
     * NullPointerException. substring(7) then skips exactly the seven
     * characters of "Bearer " -- one character less and the token would start
     * with a space, and JwtService would refuse to parse it.
     *
     * <p>JwtAuthenticationFilter, in this same folder, holds the same six
     * lines: the two filters read the same header, each one on its own.
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

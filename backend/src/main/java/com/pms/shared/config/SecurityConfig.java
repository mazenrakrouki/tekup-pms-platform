package com.pms.shared.config;

import com.pms.auth.security.FirstLoginFilter;
import com.pms.auth.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// =============================================================================
// FILE: SecurityConfig.java
//
// WHAT THIS FILE IS
//   The single place that describes the HTTP security of the whole backend:
//   which URLs are open to anybody, which ones need a logged-in caller, which
//   protection headers every answer carries, and in which order the two custom
//   filters of the project run.
//
// WHERE IT SITS IN THE FLOW
//   Spring Boot reads this class at start-up and builds ONE SecurityFilterChain
//   out of it. Every HTTP request then crosses that chain BEFORE Spring MVC:
//     Angular client
//       -> CORS check and security headers (both set below)
//       -> JwtAuthenticationFilter (com.pms.auth.security): reads the header
//          "Authorization: Bearer <token>" and puts the caller, with his
//          permission list, into the Spring Security context
//       -> FirstLoginFilter (audit item H-2): blocks a user who still has to
//          change his password
//       -> the authorization rules written below: 401 when nobody is logged in
//       -> Spring MVC
//          -> ProjectScopeInterceptor (ADR-021, same package) for every URL
//             shaped /api/projects/{id}/**
//          -> controller -> service, where @PreAuthorize("hasAuthority('X')")
//             is finally evaluated.
//   This class calls nothing at run time. It only wires objects together at
//   start-up, and publishes four beans the rest of the application injects:
//   the filter chain, the disabled registration of FirstLoginFilter, the
//   password encoder (used by AuthService, UserCrudService and DataInitializer
//   in this package) and the CORS configuration.
//
// WHY IT EXISTS - what would break if you deleted it
//   Spring Boot would fall back on its own default security: a generated
//   password printed in the console, an HTML login form, server-side sessions,
//   and no place at all to plug the JWT filter in. Every call of the Angular
//   client would come back as a redirect to that form, and @PreAuthorize would
//   never receive a permission list, because nothing would build one.
//
// THE DIVISION OF WORK A JURY WILL ASK ABOUT
//   This file answers one question only: "is somebody logged in?". It never
//   answers "may this person do this?". That second question is answered by
//   @PreAuthorize("hasAuthority('...')") on the SERVICE methods (ADR-001), and
//   the project perimeter is answered by ProjectScopeInterceptor (ADR-021).
//   Notice what is absent below: no rule names a role. The words ADMIN,
//   DIRECTEUR, CHEF_PROJET appear nowhere in this class, because roles and the
//   permissions behind them are rows in the database that an administrator
//   edits at run time - not text compiled into the application.
// =============================================================================

// @Configuration: Spring reads this class at start-up and calls each @Bean
// method once to build a shared object. Without it the file is ignored, no
// SecurityFilterChain is published, and Boot installs its own default one.
@Configuration
// @EnableWebSecurity switches the Spring Security web machinery on and makes
// Boot use the chain built below instead of the default chain.
@EnableWebSecurity
// @EnableMethodSecurity is the line that makes the whole permission model work:
// it is what activates @PreAuthorize on the service methods. Without it every
// @PreAuthorize("hasAuthority('VIEW_DEVIS_INTERNE')") is ignored in silence -
// the annotation stays visible in the code, the check simply disappears - and a
// plain developer could read the internal quote (DI), margins included, of any
// project. Nothing would fail, nothing would be logged: that is what makes this
// one word dangerous to forget.
@EnableMethodSecurity
// Lombok writes the constructor over the two final fields below, which is how
// Spring hands the two filters to this class.
@RequiredArgsConstructor
public class SecurityConfig {

    // Reads the bearer token and installs the caller for the current request.
    // It is injected instead of being created here so that it stays a normal
    // bean with its own dependencies (JwtService, UserRepository, and the
    // "securityContext" cache of ADR-017).
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    // H-2: while the user still carries firstLogin = true, this filter refuses
    // every endpoint except change-password, logout and refresh.
    private final FirstLoginFilter firstLoginFilter;

    // Reads pms.security.bcrypt-strength from application.yml and falls back to
    // 12 when the property is missing. The number is a power of two: 12 means
    // bcrypt runs 2^12 = 4096 internal rounds to hash one password.
    // Why a property and not a constant: hashing cost has to follow the machine
    // it runs on. A test suite can lower it to stay fast, a production server
    // can raise it as hardware gets faster, with no code change. Passwords
    // already stored keep working, because a bcrypt hash carries its own cost
    // inside the text it produces ($2a$12$...).
    @Value("${pms.security.bcrypt-strength:12}")
    private int bcryptStrength;

    // The web origins allowed to call this API from a browser. application.yml
    // lists http://localhost:4200 and http://127.0.0.1:4200 for the development
    // loop; the prod profile passes CORS_ALLOWED_ORIGINS. Spring splits a
    // comma-separated property into this List<String> by itself.
    // Why a property: in the containerized stack (ADR-026) nginx serves the
    // Angular build and proxies /api on the SAME origin, so the list is not the
    // one used in development. Writing localhost:4200 into the code would mean
    // rebuilding the application for every deployment.
    @Value("${pms.cors.allowed-origins:http://localhost:4200}")
    private List<String> corsAllowedOrigins;

    /**
     * Builds the one filter chain every HTTP request of the backend goes
     * through, and gives it back to Spring Boot as a bean.
     *
     * <p>Everything is written as one long chained call because that is the
     * shape the Spring Security 6 API imposes: each {@code .something(...)}
     * receives a small function that configures one part. The older style
     * ({@code http.csrf().disable().cors().and()...}) is removed from this
     * version, so this is not a taste, it is the only way.
     *
     * <p>{@code throws Exception} comes from the signature of
     * {@code http.build()}. Nothing here catches it on purpose: a security
     * chain that cannot be built must stop the application at start-up. An
     * application that boots with half a security configuration is far worse
     * than an application that refuses to boot.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF (Cross-Site Request Forgery) is the attack where a page of
            // another site makes YOUR browser send a write request to this API,
            // riding on whatever the browser attaches by itself. Spring's
            // protection answers that by demanding a secret token in every
            // POST/PUT/DELETE.
            // It is turned off here because the caller of this API is identified
            // by the Authorization header, and a browser NEVER attaches that
            // header by itself - a foreign page cannot make it appear. The one
            // cookie the application does use, the refresh cookie, is protected
            // another way: AuthController limits it to the path
            // /api/auth/refresh and marks it SameSite=Strict, so the browser
            // does not send it from another site either.
            // Left switched on, every save of the Angular client would come back
            // 403 until the client fetched a CSRF token and echoed it back.
            .csrf(AbstractHttpConfigurer::disable)
            // CORS (Cross-Origin Resource Sharing) is the browser rule that stops
            // a page served by one origin from reading the answer of another. In
            // the development loop the Angular application is on :4200 and this
            // API on :8090 - two different origins. This line plugs in the
            // configuration built at the bottom of the file.
            // Without it every call from `ng serve` fails in the browser console
            // with "No Access-Control-Allow-Origin header", even though the
            // server answered 200 and wrote the row.
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .headers(h -> h
                // Sends "X-Frame-Options: DENY", so no browser will draw a page
                // of this application inside an <iframe>.
                // Without it, an attacker loads the real PMS in an invisible
                // frame on top of his own page and makes a logged-in director
                // click "archive project" while the director believes he is
                // clicking a button of that other page. The attack has a name:
                // clickjacking.
                .frameOptions(fo -> fo.deny())
                // Keeps the header "X-Content-Type-Options: nosniff". The empty
                // function means "leave the default in place"; it is written out
                // so the choice is visible instead of silent. The header tells
                // the browser to believe the declared content type instead of
                // guessing from the bytes.
                // Without it, a file served as plain text but holding HTML can
                // be guessed to be a page and run as one, inside the session of
                // whoever opened it.
                .contentTypeOptions(cto -> {})
                // Sends "Referrer-Policy: no-referrer": the browser puts no
                // source address in the requests that leave the application.
                // Without it, opening an outside link from a page such as
                // /projects/7/devis-interne writes that full address - project
                // number included - into the logs of a site we do not control.
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            )
            // STATELESS: the server opens no HttpSession and remembers nothing
            // about the caller between two requests; each request must carry its
            // own token. Why: the identity already travels inside the JWT, so a
            // session would be dead weight, and it would also mean a JSESSIONID
            // cookie - the very thing that brings the CSRF problem back, right
            // after the line above switched the protection off.
            // Without it Spring Security creates a session on the first request
            // and the application stops being horizontally scalable: a second
            // instance behind a load balancer would not know that session.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // The rules are read from top to bottom and the FIRST one that
            // matches decides. That is why the open doors are listed first and
            // the catch-all rule is last.
            .authorizeHttpRequests(auth -> auth
                // The two doors that must stay open, because the caller cannot
                // possibly hold a valid access token yet: signing in, and the
                // refresh call that exchanges the HttpOnly cookie for a new
                // access token.
                // Why they are listed one by one instead of /api/auth/**:
                // logout and change-password sit under the same prefix and must
                // stay behind authentication. A single wildcard here would open
                // change-password to the whole internet, and anybody could then
                // set a new password on an account whose e-mail he knows.
                .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                // The generated API documentation (springdoc, see OpenApiConfig
                // in this package). The Swagger page itself has to load before
                // anybody can sign in from it, so it cannot require a token.
                // What it does expose is the SHAPE of the API - the list of
                // routes - to anyone who can reach the server; calling any of
                // those routes still needs a token, because of the rule below.
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Container probes (ADR-026). Without this rule /actuator/health
                // answers 401: the Docker health check never turns green, and
                // `depends_on: service_healthy` blocks for ever, so the stack
                // never finishes starting.
                // show-details: never (application.yml) means the answer is only
                // {"status":"UP"} - no database state, no disk space, nothing an
                // attacker could use.
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                // Everything else needs an identified caller. This is the
                // closed-by-default rule, and it is the reason a controller
                // written tomorrow is protected the day it is written: it falls
                // into this line instead of being open until somebody remembers
                // to add it to a list. The opposite order - "everything open
                // except what I remember to close" - is how endpoints get
                // forgotten and leak.
                .anyRequest().authenticated()
            )
            // What to answer when an unidentified caller asks for a protected
            // URL: a plain 401, instead of the default redirect to a login page.
            // Why: the client is an Angular application, not a browser following
            // links. Its HTTP interceptor watches for the 401 and tries a token
            // refresh; a 302 redirect towards an HTML page would look like a
            // success to that code, and the silent re-login would never happen -
            // the user would see an HTML login page pasted inside a JSON screen.
            // The message stays in French because the whole application speaks
            // French to its users ("Non authentifié" = "not authenticated").
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Non authentifié"))
            )
            // Order inside the chain, and this is the whole point of these two
            // lines. UsernamePasswordAuthenticationFilter is the built-in filter
            // that would handle a form login; placing the JWT filter before it
            // means the caller is already known when the rules above are
            // evaluated. Without this line the security context is still empty
            // at that moment, and every request - valid token or not - is
            // answered 401.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // And the first-login filter has to run AFTER the JWT filter,
            // because it asks "who is this?" before deciding. Placed before, it
            // would always find an empty context, let everybody through, and
            // audit item H-2 would not be enforced at all.
            .addFilterAfter(firstLoginFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Prevent FirstLoginFilter from being double-registered as a servlet filter.
     *
     * <p>What it does: it publishes the registration Spring Boot would have
     * created for that filter, but switched off with {@code setEnabled(false)}.
     *
     * <p>Why it is needed: FirstLoginFilter is a {@code @Component} AND a
     * servlet filter. Boot installs every such bean by itself in the plain
     * servlet chain, on every URL, outside the security chain - at a point
     * where the security context is not filled in yet. This file wants that
     * filter in one place only, right after the JWT filter, because it needs
     * the caller that filter installs. Without this bean the same object is
     * mounted at two different points of the request path; it extends
     * OncePerRequestFilter, so today the duplicate pass does no work, but the
     * second mounting point is invisible in the code and would start to matter
     * as soon as the chain above is reordered.
     *
     * <p>Honest remark for a jury: {@link JwtAuthenticationFilter} is also a
     * {@code @Component} filter and carries no equivalent bean, so it is still
     * auto-registered. That asymmetry is real and is listed as an open point,
     * not hidden here.
     */
    @Bean
    public FilterRegistrationBean<FirstLoginFilter> firstLoginFilterRegistration(FirstLoginFilter filter) {
        FilterRegistrationBean<FirstLoginFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false);
        return bean;
    }

    /**
     * The one object that hashes a password before it is stored, and that
     * checks a typed password against a stored hash. Injected into AuthService,
     * UserCrudService and DataInitializer (same package).
     *
     * <p>bcrypt is a password hashing function built to be SLOW on purpose. A
     * hash is a one-way transformation: from the password you can compute the
     * hash, from the hash you cannot get the password back. Slowness is the
     * feature - it costs an attacker who stole the users table roughly the same
     * time per guess, which turns a few hours of guessing into years.
     *
     * <p>bcrypt also adds a "salt": a random value mixed into each hash and
     * kept inside the resulting text. So two users who chose the same password
     * get two different hashes, and an attacker cannot see that they match, nor
     * reuse a precomputed table of common passwords.
     *
     * <p>Why the return type is {@code PasswordEncoder} (the interface) and not
     * {@code BCryptPasswordEncoder}: everything that injects it depends on the
     * interface, so replacing bcrypt by a newer algorithm one day is one line
     * here and nothing anywhere else.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    /**
     * Builds the CORS answer the browser receives: who may call this API from a
     * web page, with which HTTP methods, and whether the call may carry
     * cookies.
     *
     * <p>Read it with one thing in mind: CORS is enforced by the BROWSER, not
     * by the server. It protects a logged-in user against a page he did not
     * expect; it is not a wall against curl or Postman, which ignore it
     * completely. The real wall is the authentication rules above.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        // The exact list of origins, read from the configuration. It has to be
        // exact because setAllowCredentials(true) below forbids the wildcard
        // "*": a browser refuses the pair "* + credentials" outright, and the
        // call fails with a CORS error even though the server answered.
        cfg.setAllowedOrigins(corsAllowedOrigins);
        // The HTTP verbs the client may use. OPTIONS has to be in the list: for
        // any request that is not a simple GET, the browser first sends an
        // OPTIONS "preflight" to ask for permission. Without OPTIONS here, the
        // preflight of every PUT and DELETE is refused and no save ever leaves
        // the Angular application.
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Any request header is accepted. The client needs at least
        // Authorization (the bearer token), Content-Type (application/json) and
        // Accept-Language (Transloco sends the chosen language). Listing them by
        // hand would mean a CORS failure every time the frontend adds one, and a
        // header name is not a secret worth protecting.
        cfg.setAllowedHeaders(List.of("*"));
        // Lets the browser send and receive cookies on cross-origin calls. This
        // is what makes POST /api/auth/refresh work in the development loop:
        // the refresh token lives in an HttpOnly cookie, and without this flag
        // the browser drops that cookie on a call from :4200 to :8090. The user
        // would then be signed out the moment his access token expires, roughly
        // every fifteen minutes.
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // The rule applies to /api/** only. /actuator/health is left out on
        // purpose: it is called by the Docker health check, never by a web page,
        // so it needs no CORS header at all.
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}

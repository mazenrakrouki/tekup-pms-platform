package com.pms.shared.config;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

// =============================================================================
// FILE: SpringSecurityAuditorAware.java
//
// WHAT THIS FILE IS
//   The answer to one question: "who is writing to the database right now?".
//   The value it returns is what lands in the columns created_by and
//   updated_by of every table.
//
// WHERE IT SITS IN THE FLOW
//   service method -> repository.save(entity)
//     -> AuditingEntityListener (declared on BaseEntity, package
//        com.pms.shared.entity) runs just before the INSERT or the UPDATE
//     -> it calls THIS CLASS, because JpaConfig (same package) points at it
//        with @EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware")
//     -> the returned text is written into created_by / updated_by.
//   It calls nothing. It only reads the Spring Security context, which
//   JwtAuthenticationFilter filled at the beginning of the request.
//
// WHY IT EXISTS - what would break if you deleted it
//   The application would not start: JpaConfig asks Spring for a bean with this
//   exact name, and would fail with "No qualifying bean of type AuditorAware".
//   And if the reference were removed too, the dates would still be stamped but
//   created_by / updated_by would stay empty for ever - the audit trail added
//   by the Flyway migration V19 (audit item H-6) would hold no author, and
//   "who changed the price of this DI line?" would have no answer.
//
// WHY THE NAME AND NOT THE ID
//   It returns the e-mail of the user, the same text auth.getName() gives and
//   the same one carried by the JWT. A foreign key to users.id would be
//   "cleaner" in theory, and wrong in practice: an audit trail must survive the
//   row it points at. A deleted or renamed account must not erase the history
//   of what that account did, and a text column keeps the trace whatever
//   happens to the users table afterwards.
// =============================================================================

/**
 * Tells Spring Data who the current user is, so that it can stamp created_by
 * and updated_by on every row it writes.
 *
 * <p>{@code AuditorAware<String>}: the generic parameter is the type of the
 * author. {@code String} here because the audit columns are text (length 255
 * in BaseEntity). Choosing {@code AuditorAware<User>} instead would make
 * Spring store a foreign key to the users table - see the header for why that
 * is not wanted.
 *
 * <p>{@code @Component} publishes it under the default bean name
 * {@code springSecurityAuditorAware}, which is exactly the name JpaConfig
 * writes in {@code auditorAwareRef}. Renaming the class without changing that
 * text stops the application at start-up.
 */
@Component
public class SpringSecurityAuditorAware implements AuditorAware<String> {

    /**
     * Gives back the name to write in the audit columns: the e-mail of the
     * logged-in user, or the literal {@code "system"} when there is no user.
     *
     * <p>It never returns {@code Optional.empty()}, and that is a deliberate
     * choice. An empty Optional means "no author", and Spring Data then leaves
     * the column null. Writing {@code "system"} instead makes the rows created
     * outside any request - DataInitializer and the demo seeders of this
     * package, all of which run at start-up - recognisable at a glance. That
     * distinction is what lets AgileDemoSeeder replace its own rows without
     * touching a board somebody built by hand through the API.
     */
    @Override
    public Optional<String> getCurrentAuditor() {
        // The user installed by JwtAuthenticationFilter at the start of the
        // request. Null when the write happens outside an HTTP request.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Three cases mean "nobody is logged in", and all three must be tested:
        //   auth == null           -- a write during start-up (the seeders) or
        //                             in a test with an empty context. Without
        //                             this test, auth.getName() below throws a
        //                             NullPointerException and the application
        //                             dies while booting;
        //   !auth.isAuthenticated()-- a half-built authentication object;
        //   "anonymousUser"        -- the placeholder Spring Security puts in
        //                             place on a public endpoint. It is a real
        //                             object, so the two tests above do not
        //                             catch it; without this third test,
        //                             created_by would literally read
        //                             "anonymousUser", which looks like a user
        //                             name and is not one.
        // getPrincipal() returns Object, so the comparison is written with the
        // constant first: "anonymousUser".equals(x) can never throw, while
        // x.equals("anonymousUser") would throw if x were null.
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.of("system");
        }
        // getName() is the e-mail: JwtAuthenticationFilter builds the
        // Authentication with the e-mail as the principal.
        return Optional.of(auth.getName());
    }
}

package com.pms.shared.config;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

// Answers "who is writing to the database right now?" for JPA auditing (created_by/updated_by
// on every table). Returns the user's e-mail, not a users.id foreign key, so the audit trail
// survives a deleted or renamed account.

/**
 * Tells Spring Data the current user, to stamp created_by/updated_by on every write.
 * {@code @Component} publishes it as bean name {@code springSecurityAuditorAware}, the exact
 * name JpaConfig references in {@code auditorAwareRef} — renaming this class breaks that link.
 */
@Component
public class SpringSecurityAuditorAware implements AuditorAware<String> {

    /**
     * The logged-in user's e-mail, or {@code "system"} when there is none. Never returns
     * empty: that would leave the column null, whereas "system" marks rows written by
     * start-up seeders as distinguishable from ones created through the API.
     */
    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // auth==null: write during start-up/tests. !isAuthenticated: half-built auth.
        // "anonymousUser": Spring Security's placeholder on public endpoints — a real object,
        // so it needs its own check, or created_by would literally read "anonymousUser".
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.of("system");
        }
        return Optional.of(auth.getName());
    }
}

package com.pms.user.dto;

import java.util.Set;

// Answers "who am I, and what am I allowed to do?" for the signed-in user: identity, role name,
// permission codes, and the first-login flag. No @PreAuthorize guards it — reading your own
// context needs no particular permission — SecurityConfig's anyRequest().authenticated() is the
// gate. What it returns only decides what the browser DISPLAYS; every real action is re-checked
// server-side by @PreAuthorize (and ProjectScopeInterceptor for project-scoped URLs).

/**
 * The context of the signed-in user.
 *
 * <p>Built by hand in UserService.getContext(): the permissions field isn't a copy of anything on
 * the entity, it's computed by walking role -> permissions and keeping the codes.
 */
public record UserContextResponse(
        // Database id; AuthService exposes it as currentUserId for "is this task mine?" checks.
        Long id,

        // "Firstname Lastname" for the top bar.
        String fullName,

        // The login e-mail; also identifies the session for the ADR-017 "securityContext" cache.
        String email,

        // The role NAME, display only — no authorization decision in this project reads this
        // string; access is always decided from the permissions field below.
        String role,

        // The permission codes the person holds, used by Angular guards and *ngIf-style checks.
        // Set, not List, since Role.permissions is itself a Set. Rebuilt on every call (not
        // trusted from the token) so a permission an admin just revoked stops applying at once,
        // instead of surviving until the person logs out (ADR-017, dynamic RBAC).
        Set<String> permissions,

        // true = still on the admin-generated password. Drives the redirect to the
        // change-password page; the real guard is FirstLoginFilter, which refuses every other
        // endpoint while this is true.
        boolean firstLogin
) {}

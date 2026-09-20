package com.pms.auth.dto;

import java.util.Set;

// JSON body returned after a successful login or token refresh. Built only in
// AuthService.buildBundle. The refresh token is deliberately NOT a field here — it travels in
// a separate HttpOnly cookie (see TokenBundle) so an XSS payload reading this JSON can't get a
// long-lived token, only the short-lived access token (H-1).

/**
 * What the client gets back from POST /api/auth/login and POST /api/auth/refresh.
 *
 * <p>A record, not a class with setters, so nothing between AuthService building it and Jackson
 * serializing it can add a permission to the list.
 *
 * <p>Built by hand rather than by MapStruct (ADR-018): it isn't a copy of one entity — three
 * fields (accessToken, role name, permission codes) are computed, not read directly.
 */
public record AuthResponse(
        // Database id of the account. Angular keeps it to answer "is this task mine?" locally.
        Long userId,

        // The short-lived JWT sent back as "Authorization: Bearer <accessToken>". Travels in
        // the body (not a cookie) because JavaScript has to read it to build that header; the
        // short lifetime plus the tokenVersion revocation claim bound the risk.
        String accessToken,

        // true = still using the administrator-generated password. Server-enforced too:
        // FirstLoginFilter blocks everything except change-password/logout/refresh while this
        // is true (H-2), so the client can't just skip the redirect.
        boolean firstLogin,

        // The login e-mail; also the key of the "securityContext" cache (ADR-017).
        String email,

        // "Firstname Lastname" for the top bar. Computed by User.getFullName(), no stored column.
        String fullName,

        // The role NAME, display only. No authorization decision reads this field — access is
        // decided from the permission codes below via @PreAuthorize("hasAuthority('CODE')").
        String role,

        // Permission codes held by the user, e.g. ["VIEW_PROJECT", "ASSIGN_DEVELOPER"]. Rebuilt
        // on every login and refresh so a permission an admin just granted (or revoked) shows up
        // without a redeploy — the dynamic half of RBAC.
        Set<String> permissions
) {}

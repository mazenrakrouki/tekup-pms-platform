package com.pms.user.service;

import com.pms.shared.exception.NotFoundException;
import com.pms.user.dto.UserContextResponse;
import com.pms.user.entity.Permission;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

// The "who am I?" service: for the signed-in caller, returns name, email, role label and
// the exact set of permission codes held right now. Called from GET /api/me/context, with
// the email taken from @AuthenticationPrincipal (the already-verified JWT), never the
// request body — a caller cannot ask for somebody else's context.
//
// Display only: hiding a menu entry protects nothing. Real authorization is
// @PreAuthorize("hasAuthority('X')") on service methods plus the ADR-021 project scope;
// editing this JSON in the browser still gets 403 on the actual call.
//
// Separate from UserCrudService (which administers OTHER people under MANAGE_USERS): this
// method only returns the caller's own row, so it carries no @PreAuthorize — being
// authenticated is the whole requirement.

/**
 * Read-only service that builds the session context of the current user.
 *
 * <p>Holds a repository rather than a mapper because the permission set has to be computed
 * (MapStruct/ADR-018 only covers a plain entity-to-DTO copy).
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * Builds the signed-in user's context: id, full name, email, role name, permission code
     * set, and firstLogin flag.
     *
     * <p>Permissions are recomputed from the database on every call rather than read from
     * the JWT, so a role-matrix change is reflected the moment the page is refreshed
     * instead of staying stale for the token's whole life (ADR-001).
     *
     * <p>Throws NotFoundException (404) when no active account matches — e.g. the account
     * was deleted while the person still held a valid token.
     */
    // readOnly: reads the user then walks role.permissions inside one transaction; without
    // it a stray setter further down could get silently flushed on a "read my profile" call.
    @Transactional(readOnly = true)
    public UserContextResponse getContext(String email) {
        // JOIN FETCH on role and permissions in the same query: open-in-view is false, so a
        // lazy load here would throw LazyInitializationException.
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + email));

        // Set, not List: the front end only ever asks "do I hold code X?", and only the code
        // travels — the browser has no use for the permission's id, module or description.
        var permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());

        return new UserContextResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                // Display label only — access decisions always use the permission codes
                // above, never this string (ADR-001).
                user.getRole().getName(),
                permissions,
                // Angular router reads this to redirect to the change-password page; sent
                // here rather than trusted from inside the JWT, and enforced server-side by
                // FirstLoginFilter regardless.
                user.isFirstLogin()
        );
    }
}

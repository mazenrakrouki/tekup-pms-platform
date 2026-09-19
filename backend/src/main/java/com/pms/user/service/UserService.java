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

// =============================================================================
// FILE: UserService.java
//
// WHAT THIS FILE IS
//   The "who am I?" service. It answers one question for the person who is
//   already signed in: what is my name, my e-mail, my role label, and above all
//   the exact list of permission codes I hold right now.
//
// WHERE IT SITS IN THE FLOW
//   Called by:  UserController, on GET /api/me/context. The e-mail comes from
//     @AuthenticationPrincipal, that is from the JWT that
//     JwtAuthenticationFilter has already checked -- never from the request
//     body. So a caller cannot ask for somebody else's context.
//   Calls:      UserRepository.findActiveByEmailWithRole(...), which brings the
//     user, its role and the role's permissions back in one single SELECT.
//   Gives back: UserContextResponse, turned into JSON for the Angular app. The
//     front end keeps it and builds the dynamic menu from the "permissions"
//     set: a menu entry is drawn only when its code is inside that set.
//
// WHY IT EXISTS
//   Delete it and the Angular application has no way to know what the signed-in
//   person may do. It would have to guess from a role name, which ADR-001
//   forbids, and the dynamic permission matrix would become invisible to the
//   user interface.
//
// IMPORTANT FOR THE JURY -- this list is for DISPLAY only.
//   Hiding a button protects nothing. The real authorization happens on the
//   server, with @PreAuthorize("hasAuthority('X')") written on SERVICE methods
//   (see UserCrudService, RoleAdminService and ResourceService in this same
//   folder), plus the project-scope check of ADR-021 for /api/projects/{id}/**.
//   Somebody who edits this JSON in the browser still receives 403 on the call.
//
// WHY THIS CLASS IS SEPARATE FROM UserCrudService
//   Different audience, different guard. UserCrudService administers OTHER
//   people and every method there is gated by MANAGE_USERS. This method returns
//   only the caller's own row, so it carries no @PreAuthorize at all: being
//   authenticated is the whole requirement.
// =============================================================================

/**
 * Read-only service that builds the session context of the current user.
 *
 * <p>Why it holds a repository and not a mapper: the answer mixes three things
 * that live in two different tables (the user row, the role name, the set of
 * permission codes) and flattens them into one small object. MapStruct is the
 * mandatory tool for a plain entity-to-DTO copy (ADR-018); here the permission
 * set has to be computed, so the assembly is written by hand in one place.
 */
@Service
// @RequiredArgsConstructor (Lombok) writes the constructor that takes every
// "final" field, which is how Spring injects UserRepository.
// Why constructor injection and not @Autowired on the field: the field can then
// stay final, so the service cannot be built half-empty, and a plain unit test
// can pass a fake repository without any Spring machinery.
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * Builds the context of the signed-in user from their e-mail.
     *
     * <p>Gives back a UserContextResponse: id, full name, e-mail, role name,
     * the set of permission codes, and the firstLogin flag. (A DTO -- Data
     * Transfer Object -- is a small flat object built only to travel over HTTP;
     * it carries no database behaviour.)
     *
     * <p>Why the permission codes are recomputed from the database on every
     * call instead of being read from the JWT: the token is signed once and
     * then stays the same for its whole life. If an administrator changes the
     * role matrix in the meantime, a token-based list would keep showing the
     * old menu. Reading the database here means the menu follows the matrix as
     * soon as the page is refreshed -- which is the whole promise of the
     * dynamic RBAC of ADR-001.
     *
     * <p>Throws NotFoundException (HTTP 404) when no active account matches the
     * e-mail. That happens when the account was deleted while the person was
     * still holding a valid token.
     */
    // @Transactional(readOnly = true) makes the whole method one single
    // read-only unit of work with the database.
    // Why: the method reads the user, then walks role.permissions. Keeping both
    // inside one transaction keeps them consistent, and readOnly tells
    // Hibernate it does not need to compare the loaded objects with the
    // database at the end (no dirty checking, no flush).
    // Without it: any setter called by mistake further down the chain would be
    // silently written back to the users table at commit time, so a simple
    // "read my profile" call could modify data.
    @Transactional(readOnly = true)
    public UserContextResponse getContext(String email) {
        // findActiveByEmailWithRole does "JOIN FETCH u.role LEFT JOIN FETCH
        // r.permissions ... AND u.deleted = false". The JOIN FETCH part loads
        // the role and its permissions in the SAME query as the user.
        // Why that matters here: application.yml sets open-in-view: false, so
        // the database session is closed as soon as this method returns. If the
        // permissions were loaded lazily, the line below would throw
        // LazyInitializationException and the whole application would fail to
        // start its menu after login.
        //
        // orElseThrow turns "no row" into a clear 404 instead of a null.
        // Without it, user would be null and the next line would throw
        // NullPointerException, which the browser would see as a 500 "server
        // error" with no usable message.
        // The text was "Utilisateur introuvable" (user not found) in French.
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + email));

        // Turns the Permission rows into plain strings, for example
        // "MANAGE_USERS", "VIEW_RESOURCES".
        // stream() walks the set, map(Permission::getCode) keeps only the code
        // column, collect(toSet()) gathers them into a Set.
        // WHY a Set and not a List: the front end only ever asks "do I hold
        // code X?". A Set says exactly that, cannot contain the same code
        // twice, and serialises to a JSON array all the same.
        // WHY only the code and not the whole Permission object: the browser
        // has no use for the database id, the module or the description on this
        // screen, and every extra field sent is one more thing leaving the
        // server for nothing.
        // Without this mapping the response would carry entity objects, and
        // Jackson would follow Permission -> BaseEntity and expose internal
        // audit columns.
        var permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());

        return new UserContextResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                // The role NAME travels as a label for the screen only ("ADMIN",
                // "CHEF_PROJET"). Nothing in this project decides an access
                // right by comparing this string: the decision is always made
                // from the permission codes above (ADR-001).
                user.getRole().getName(),
                permissions,
                // true = the person still uses the password the administrator
                // generated. The Angular router reads this flag and sends them
                // straight to the change-password page.
                // Why send it here as well, when the JWT already carries it:
                // the front end must not read and trust the inside of the
                // token; it receives its state from this endpoint. On the
                // server side the rule is enforced by FirstLoginFilter, which
                // blocks every other endpoint until the password is changed.
                user.isFirstLogin()
        );
    }
}

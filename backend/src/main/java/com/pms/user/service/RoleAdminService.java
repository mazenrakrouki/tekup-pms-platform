package com.pms.user.service;

import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.dto.PermissionResponse;
import com.pms.user.dto.RoleRequest;
import com.pms.user.dto.RoleResponse;
import com.pms.user.entity.Permission;
import com.pms.user.entity.Role;
import com.pms.user.repository.PermissionRepository;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// =============================================================================
// FILE: RoleAdminService.java
//
// WHAT THIS FILE IS
//   The service that lets an administrator manage roles: list them, create one,
//   rename it, change which permissions it carries, and delete it. It is the
//   part of the application where the authorization matrix is actually edited.
//
// WHERE IT SITS IN THE FLOW
//   Called by:  RoleController, on /api/admin/roles (GET list, GET /{id},
//     POST, PUT /{id}, DELETE /{id}), which the Angular "Roles" admin page
//     drives.
//   Calls:      RoleRepository (the roles), PermissionRepository (to turn the
//     ids sent by the screen into real Permission rows), UserRepository (to
//     count how many people carry a role) and the Spring CacheManager (to drop
//     the cached security contexts once the matrix changed).
//   Produces:   RoleResponse, each one carrying a sorted list of
//     PermissionResponse -- the same record PermissionAdminService produces, so
//     the two admin screens speak the same shape.
//
//   Sister service: PermissionAdminService, in this same folder, SHOWS the
//   catalogue of permissions. This class is the one that CHANGES who holds
//   what. Both are gated by the same permission, MANAGE_ROLES.
//
// WHY IT EXISTS
//   It is the realization of ADR-001. Without it, giving project managers one
//   extra right would mean an UPDATE written by hand in the database, or a code
//   change and a redeployment. Here it is a checkbox on a screen.
//
// THE THREE RULES THIS CLASS ENFORCES (translated from the original French)
//   1. A SYSTEM role -- ADMIN, DIRECTEUR, CHEF_PROJET, DEVELOPPEUR -- can be
//      neither renamed nor deleted. Their names are referenced by the seed data
//      and the migrations. Their PERMISSIONS stay editable, and that is exactly
//      the point of the dynamic matrix: the row is protected, not its content.
//   2. A role that is still carried by at least one user cannot be deleted.
//   3. Any change to a role's permissions clears the security-context cache, so
//      that sessions already open rebuild their authority list from the
//      database (ADR-017).
//
// WHY RULE 3 MATTERS -- the question a jury will ask
//   JwtAuthenticationFilter caches the authority list under the key
//   "email:tokenVersion" (Caffeine, expireAfterWrite=5m in application.yml), so
//   that it does not hit the database on every single request. If the cache
//   were not cleared, an administrator could remove MANAGE_DI from a role and
//   the people holding that role would keep using it for up to five more
//   minutes. Clearing it makes the change take effect on the very next request.
// =============================================================================

/**
 * Role administration (dynamic RBAC -- ADR-001), guarded by
 * {@code MANAGE_ROLES}.
 *
 * <p>Why every method carries its own {@code @PreAuthorize} instead of one
 * annotation on the class: the guard is written next to the thing it protects,
 * so a method added later is visibly unprotected in review rather than
 * silently inheriting a rule nobody re-read.
 */
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    // Used only to COUNT the users of a role: the screen shows the number, and
    // delete() refuses when it is not zero.
    private final UserRepository userRepository;
    // Spring's handle on the Caffeine caches declared in application.yml. Here
    // it gives access to the "securityContext" cache that JwtAuthenticationFilter
    // fills.
    private final CacheManager cacheManager;

    /**
     * Lists every role, sorted by name, each one with its permissions and the
     * number of users who carry it.
     *
     * <p>Gives back a list of RoleResponse. The user count comes from a COUNT
     * query per role (see toResponse), which is acceptable here because the
     * table holds a handful of roles, not thousands.
     */
    // Same reasoning as in PermissionAdminService: the check sits on the
    // SERVICE, so it protects every caller and not only the HTTP door, and it
    // names a permission, never a role name (ADR-001).
    // Without it, any signed-in developer could read the whole security matrix
    // of the application, which is a map of where to attack.
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public List<RoleResponse> findAll() {
        return roleRepository.findAll().stream()
                // Soft delete: BaseEntity keeps a "deleted" flag and rows stay
                // in the table. findAll() is the plain Spring Data method, so
                // it returns them too and they must be dropped here, otherwise
                // a retired role would reappear in the admin list.
                .filter(r -> !r.isDeleted())
                // Alphabetical order by name. Without it the list comes back in
                // whatever order the database chose, and the same screen
                // reloaded twice could show the rows in a different order.
                .sorted(Comparator.comparing(Role::getName))
                // this::toResponse is a method reference: "for each role, call
                // toResponse on it". See that private method below.
                .map(this::toResponse)
                .toList();
    }

    /**
     * Returns one role by its database id, with its permissions.
     *
     * <p>Used by the admin screen when opening the edit form. Throws
     * NotFoundException (HTTP 404) when the id does not exist or points at a
     * soft-deleted row -- see loadRole below.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public RoleResponse findById(Long id) {
        return toResponse(loadRole(id));
    }

    /**
     * Creates a new role from the name, the description and the set of
     * permission ids sent by the admin screen.
     *
     * <p>Gives back the saved role as a RoleResponse, so the screen can show
     * the generated id straight away.
     *
     * <p>Why no cache eviction here, unlike update() and delete(): a role that
     * has just been created carries no user yet, so no open session can be
     * affected by it. Clearing the cache would only throw away the authority
     * lists of everybody else for nothing.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    // @Transactional (without readOnly) makes this whole method one single
    // database unit of work that can write.
    // Why: the duplicate-name check and the INSERT must not be separated. It
    // also means that if resolvePermissions throws below, nothing at all is
    // written -- no half-created role with an empty permission set.
    @Transactional
    public RoleResponse create(RoleRequest request) {
        // trim() removes the spaces around the typed name. Why: "  AUDITEUR "
        // and "AUDITEUR" would otherwise be two different rows for the database
        // unique rule, and the second one would pass the check below and then
        // look like a duplicate on screen.
        String name = request.name().trim();
        // The check is done here so the user gets a clear message. The database
        // also has the constraint uk_roles_name, which is the real guarantee if
        // two administrators save at the very same moment.
        // IllegalArgumentException is mapped to HTTP 409 Conflict by
        // GlobalExceptionHandler -- the project reserves 409 for duplicated
        // data and 422 (BusinessRuleException) for a broken business rule.
        // Message in French: "a role already has this name".
        if (roleRepository.existsByName(name)) {
            throw new IllegalArgumentException("Un rôle porte déjà ce nom : " + name);
        }
        // The builder names every value. The all-args constructor of Role takes
        // several fields in a row, so swapping the name and the description
        // would compile without a warning.
        Role role = Role.builder()
                .name(name)
                .description(blankToNull(request.description()))
                // A role created from the screen is NEVER a system role. This
                // is written explicitly rather than left to the default,
                // because it is the flag that decides whether the row can be
                // renamed or deleted later. If a created role came out with
                // system = true, nobody could ever remove it again.
                .system(false)
                .permissions(resolvePermissions(request.permissionIds()))
                .build();
        return toResponse(roleRepository.save(role));
    }

    /**
     * Updates a role: its name, its description and above all the set of
     * permissions it carries.
     *
     * <p>Gives back the saved role. Throws BusinessRuleException (HTTP 422)
     * when somebody tries to rename a system role, and IllegalArgumentException
     * (HTTP 409) when the new name is already taken.
     *
     * <p>Why the permissions are REPLACED and not merged: the screen always
     * sends the complete list of ticked boxes. Merging would make it impossible
     * to remove a permission -- unticking a box would simply do nothing.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional
    public RoleResponse update(Long id, RoleRequest request) {
        Role role = loadRole(id);
        String name = request.name().trim();

        // Rule 1: a system role keeps its name. The test is "is it a system
        // role AND is the name actually changing", so saving a system role
        // without touching its name still works -- which is what happens every
        // time an administrator only edits its permissions.
        // Why the name is protected: ADMIN, DIRECTEUR, CHEF_PROJET and
        // DEVELOPPEUR are written in the Flyway seed files and in the demo
        // data. Renaming ADMIN to "Administrateur" would leave those
        // references pointing at nothing on the next migration.
        // BusinessRuleException maps to 422, the status this project uses for
        // "your request is well formed but breaks a rule".
        if (role.isSystem() && !role.getName().equals(name)) {
            throw new BusinessRuleException("Un rôle système ne peut pas être renommé.");
        }
        // The duplicate check runs only when the name really changes.
        // Why the first half of the condition is needed: without it, saving a
        // role without renaming it would find its OWN row through
        // existsByName and refuse the save with "this name is already taken".
        if (!role.getName().equals(name) && roleRepository.existsByName(name)) {
            throw new IllegalArgumentException("Un rôle porte déjà ce nom : " + name);
        }

        role.setName(name);
        role.setDescription(blankToNull(request.description()));
        // Replaces the whole permission set. Hibernate works out the difference
        // and writes the corresponding INSERT and DELETE rows in the join table
        // role_permissions.
        role.setPermissions(resolvePermissions(request.permissionIds()));

        RoleResponse saved = toResponse(roleRepository.save(role));
        // Rule 3 (ADR-017). Original French note: the open sessions will
        // rebuild their authorities.
        // WHAT: throws away every cached authority list.
        // WHY: JwtAuthenticationFilter keeps the authority list of each session
        // in the "securityContext" cache to avoid a database round trip on
        // every request. That cached list was built from the OLD matrix.
        // WITHOUT IT: an administrator removes MANAGE_DI from CHEF_PROJET, and
        // every project manager already signed in keeps opening the internal
        // quotes for up to five more minutes (expireAfterWrite=5m).
        evictSecurityContextCache();
        return saved;
    }

    /**
     * Deletes a role.
     *
     * <p>Returns nothing. Refuses with BusinessRuleException (HTTP 422) in two
     * cases: the role is a system role, or at least one user still carries it.
     *
     * <p>Why the second guard exists: the role_id column of the users table is
     * NOT NULL. Deleting a role that people still carry would break that
     * foreign key, and if the database let it through those people would have
     * no role, therefore no permission, therefore no access to any screen --
     * with no way back through the interface.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional
    public void delete(Long id) {
        Role role = loadRole(id);
        // Rule 1 again: the four built-in roles are permanent. Deleting ADMIN
        // would leave the platform with nobody able to administer it.
        if (role.isSystem()) {
            throw new BusinessRuleException("Un rôle système ne peut pas être supprimé.");
        }
        // Rule 2. The count ignores soft-deleted users, because a removed
        // account must not block the cleanup of a role.
        long users = userRepository.countByRoleIdAndDeletedFalse(id);
        if (users > 0) {
            // The message gives the number on purpose: the administrator then
            // knows how many people have to be reassigned first.
            // French text: "this role is assigned to N user(s) -- reassign them
            // before deleting".
            throw new BusinessRuleException(
                    "Ce rôle est affecté à " + users + " utilisateur(s) — réaffectez-les avant suppression.");
        }
        // Original French note kept: role_permissions is purged by ON DELETE
        // CASCADE (V1).
        // WHAT: a real DELETE of the row, not the soft delete used elsewhere in
        // the project.
        // WHY it is safe here: the two guards above have already proved that no
        // user points at this role, and the rows of the join table
        // role_permissions are removed by the database itself thanks to the
        // ON DELETE CASCADE declared in V1__schema_auth.sql.
        // WITHOUT that cascade: the delete would fail with a foreign-key error
        // as soon as the role carried a single permission.
        roleRepository.delete(role);
        // Same reason as in update(): a deleted role must not survive inside a
        // cached authority list.
        evictSecurityContextCache();
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Turns the set of permission ids sent by the screen into the real
     * Permission rows, and refuses the whole request if a single one is
     * unknown.
     *
     * <p>Gives back a Set of Permission, empty when the caller sent nothing.
     *
     * <p>Why the strict check instead of silently keeping the ids that exist:
     * an unknown id means the screen and the database disagree. Saving only
     * part of what was ticked would give the role a permission set the
     * administrator never chose, and nothing on screen would say so. Failing
     * loudly with 404 is the safe behaviour for a security matrix.
     */
    private Set<Permission> resolvePermissions(Set<Long> ids) {
        // Null handling: the field is optional in the JSON, so the record can
        // arrive with null. An empty set is a legitimate answer -- a role with
        // no permission at all is allowed (see the LEFT JOIN note in
        // UserRepository.findActiveByEmailWithRole).
        // Without this line the next call would throw NullPointerException on
        // the very first role created without ticking anything.
        if (ids == null || ids.isEmpty()) return new HashSet<>();
        // findAllById fires ONE query with "WHERE id IN (...)" instead of one
        // query per id. The result is wrapped in a HashSet because
        // Role.permissions is declared as a Set.
        Set<Permission> found = new HashSet<>(permissionRepository.findAllById(ids));
        // findAllById simply skips the ids it does not find, it does not
        // complain. Comparing the two sizes is what turns that silence into an
        // error.
        // EXAMPLE of what goes wrong without it: the screen was left open while
        // a migration retired a permission; the administrator saves, four of
        // the five ticked boxes are stored, and the role quietly loses a right
        // nobody asked to remove.
        if (found.size() != ids.size()) {
            // "One or more permissions were not found."
            throw new NotFoundException("Une ou plusieurs permissions sont introuvables.");
        }
        return found;
    }

    /**
     * Loads one role by id, or throws NotFoundException (HTTP 404).
     *
     * <p>Why this helper exists instead of calling findById at each place: the
     * soft-delete filter below has to be applied every single time. Writing it
     * once means the four public methods cannot forget it.
     */
    private Role loadRole(Long id) {
        return roleRepository.findById(id)
                // The soft-delete guard. findById is the plain Spring Data
                // method and knows nothing about the "deleted" flag, so a
                // retired role would come back and could be edited again.
                // filter() on an Optional turns "found but deleted" into
                // "empty", which the next line reports as 404.
                .filter(r -> !r.isDeleted())
                // "Role not found: <id>"
                .orElseThrow(() -> new NotFoundException("Rôle introuvable : " + id));
    }

    /**
     * Copies one Role entity into the RoleResponse record the API sends back.
     *
     * <p>Gives back id, name, description, the system flag, the number of users
     * carrying the role, and the sorted list of its permissions.
     *
     * <p>Why this mapping is hand-written while ADR-018 makes MapStruct the
     * rule for entity-to-DTO copies: two of the six values are not a copy at
     * all. userCount comes from a separate COUNT query, and the permission list
     * has to be sorted before it travels. A MapStruct mapper would need a
     * custom method for each, which is more machinery than the six lines below.
     */
    private RoleResponse toResponse(Role role) {
        List<PermissionResponse> perms = role.getPermissions().stream()
                // Same two-level order as PermissionAdminService: module first,
                // then code. Why it matters here too: Role.permissions is a
                // HashSet, and a HashSet has NO order at all. Without this
                // line the edit screen would show the checkboxes shuffled
                // differently after each save.
                .sorted(Comparator.comparing(Permission::getModule).thenComparing(Permission::getCode))
                .map(p -> new PermissionResponse(p.getId(), p.getCode(), p.getModule(), p.getDescription()))
                .toList();
        // How many people carry this role. It is shown in the list and it is
        // the value delete() checks. Soft-deleted users are excluded, so a
        // removed account does not keep a role alive forever.
        // Note for the reader: called from inside the stream of findAll(), this
        // fires one COUNT per role. That is accepted because the roles table
        // holds a handful of rows and the page is administrator-only.
        long userCount = userRepository.countByRoleIdAndDeletedFalse(role.getId());
        return new RoleResponse(role.getId(), role.getName(), role.getDescription(),
                role.isSystem(), userCount, perms);
    }

    /**
     * Empties the whole security-context cache (ADR-017).
     *
     * <p>Returns nothing. Called after update() and delete().
     *
     * <p>Why the WHOLE cache and not one entry: the cache is keyed by
     * "email:tokenVersion", one entry per open session. Changing a role affects
     * every user who carries it, and this service does not know who they are
     * without another query. Clearing everything costs one extra database read
     * per active session on their next request, which is cheap compared with
     * letting a removed permission stay usable.
     */
    private void evictSecurityContextCache() {
        Cache cache = cacheManager.getCache("securityContext");
        // Null handling: getCache returns null when no cache with that name is
        // configured -- which is what happens in a test that starts the context
        // without the Caffeine settings of application.yml. The check keeps
        // those tests from failing with NullPointerException on a line that has
        // nothing to do with what they test.
        if (cache != null) cache.clear();
    }

    /**
     * Turns an empty or whitespace-only text into null, and trims the rest.
     *
     * <p>Why: the description column is nullable, and the browser sends "" for
     * an untouched text box. Without this, half the roles would hold null and
     * the other half an empty string for the very same thing, and any later
     * "has a description?" test would have to check both.
     *
     * <p>static because it uses no field of the service: it is a pure text
     * helper, and saying so makes that obvious to the reader.
     */
    private static String blankToNull(String s) {
        // isBlank() is true for "" and also for "   ", which trim() alone would
        // not catch before the comparison.
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}

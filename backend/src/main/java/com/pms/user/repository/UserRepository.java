package com.pms.user.repository;

import com.pms.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for the users table — the accounts people sign in with. Read/written
 * through UserCrudService (@PreAuthorize MANAGE_USERS, administration) and UserService
 * (the caller's own profile). User rows never leave the server as-is: UserMapper strips
 * the BCrypt password hash before anything becomes JSON.
 *
 * <p>findActiveByEmailWithRole (below) is the entry point of authentication: called by
 * UserDetailsServiceImpl and JwtAuthenticationFilter to build the Spring Security authority
 * list, by AuthService on login/refresh/logout/password change, by UserService for
 * /api/me/context, by several services to resolve the caller's own row, and by the seed
 * loaders to check whether an account already exists.
 *
 * <p>Soft delete shapes every query here: deleting a user sets users.deleted = true and
 * the row stays, so every method filters "u.deleted = false" by hand. V18 replaced the
 * absolute unique constraint on email with a PARTIAL index (WHERE deleted = false), so a
 * deleted account's email can be reused. Note "deleted" and "active" are different: no
 * query here filters on "active" — a deactivated account must still be visible to an
 * administrator so it can be reactivated; being switched off is enforced elsewhere
 * (UserDetailsServiceImpl, AuthService, revokeAllTokens).
 *
 * <p>Security note: neither MANAGE_USERS nor the ADR-021 project scope is checked here —
 * both live on the calling services. A method of this file called directly could list
 * every account of the company.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    // Loads one live account by email with its role AND the role's permissions in a single
    // statement — user.getRole().getPermissions() IS the authority list @PreAuthorize
    // tests (ADR-001).
    // JOIN FETCH u.role is a plain inner join (role_id is NOT NULL, so nothing is lost) and
    // avoids a second round trip despite EAGER mapping.
    // LEFT JOIN FETCH r.permissions MUST STAY LEFT: a role can legitimately hold zero
    // permissions (freshly created, or just emptied). A plain JOIN would drop such an
    // account entirely, and the person would be told "Identifiants incorrects" — pointing
    // at the password — while it's the account's permission set that's empty.
    // No "u.active = true": an inactive account must still load, so AuthService can answer
    // "Compte desactive" instead of a wrong "bad credentials", and so it can be reactivated.
    // No DISTINCT despite fetching a collection: Hibernate 6 (Spring Boot 3.3) de-duplicates
    // parent rows itself.
    // Not run on every request: JwtAuthenticationFilter caches the built authority list
    // under "email:tokenVersion", so this only runs on a cache miss; bumping tokenVersion
    // makes the old cache key unreachable (ADR-017).
    // A null email (e.g. ResourceService.currentUserId() with no authenticated user) simply
    // matches nothing and returns an empty Optional — not a crash.
    @Query("SELECT u FROM User u JOIN FETCH u.role r LEFT JOIN FETCH r.permissions WHERE u.email = :email AND u.deleted = false")
    Optional<User> findActiveByEmailWithRole(String email);

    // Every live account with its role loaded, sorted by last name then first name.
    // JOIN FETCH avoids the classic N+1 (UserMapper reads role.name for every row, and
    // open-in-view is false). Permissions are deliberately NOT fetched here — this list
    // only needs name/role/email, and pulling every role's full permission set would
    // multiply rows for nothing.
    // CAREFUL: returns every account of the company. Safe only because both callers
    // (UserCrudService.findAll, guarded by MANAGE_USERS, and findAssignable, guarded by
    // hasAnyAuthority('ASSIGN_CHEF_PROJET','ASSIGN_DEVELOPER','MANAGE_USERS')) enforce
    // their own permission first.
    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.deleted = false ORDER BY u.lastName, u.firstName")
    List<User> findAllActive();

    // One live account by id. Unlike the inherited findById, this respects the soft-delete
    // flag — needed so a backlog card can't be assigned to an account deleted last month.
    // No JOIN FETCH: the only caller (BacklogItemService.resolveAssignee) never reads the
    // role, just stores the User as an assignee.
    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deleted = false")
    Optional<User> findActiveById(Long id);

    // Same as findAllActive, but paged — Page<User> carries the total count too, for "page
    // 2 of 7". countQuery is hand-written because Spring Data's auto-generated count query
    // would keep the JOIN FETCH, which Hibernate refuses when only a number is selected.
    // Paging + JOIN FETCH is safe here because the fetched link is @ManyToOne (one row per
    // user); fetching a collection instead would force Hibernate into in-memory paging.
    // No ORDER BY in the text — Spring Data appends one from the Pageable's Sort;
    // UserController's @PageableDefault(sort = "lastName") is what keeps pages stable.
    @Query(value = "SELECT u FROM User u JOIN FETCH u.role WHERE u.deleted = false",
           countQuery = "SELECT COUNT(u) FROM User u WHERE u.deleted = false")
    Page<User> findAllActivePaged(Pageable pageable);

    // The admin screen's paged search: free text (first/last name or email), role, and
    // active state, each optional. One query with self-switching filters
    // (":x = '' OR ..." / ":x IS NULL OR ...") instead of one query per combination.
    // UserCrudService normalizes a null search to "" before calling — a null inside
    // LOWER(...) makes PostgreSQL guess the parameter type as bytea and fail.
    // LIKE is wrapped in LOWER(...) on both sides for case-insensitive matching; a leading
    // '%' means no index is used, acceptable at this table's size.
    // The count query drops the JOIN FETCH (same reason as findAllActivePaged) and writes
    // the role filter as u.role.id instead of joining an alias — both queries must keep the
    // same WHERE clause or the page and its reported total would disagree.
    @Query(value = """
            SELECT u FROM User u JOIN FETCH u.role r
            WHERE u.deleted = false
              AND (:search = ''
                   OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.lastName)  LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.email)     LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:roleId IS NULL OR r.id = :roleId)
              AND (:active IS NULL OR u.active = :active)
            """,
           countQuery = """
            SELECT COUNT(u) FROM User u
            WHERE u.deleted = false
              AND (:search = ''
                   OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.lastName)  LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.email)     LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:roleId IS NULL OR u.role.id = :roleId)
              AND (:active IS NULL OR u.active = :active)
            """)
    Page<User> searchActive(String search, Long roleId, Boolean active, Pageable pageable);

    // Derived query: true when a live account already uses this email. "DeletedFalse" is
    // required or an email freed by a soft-deleted account would look taken forever.
    // Not the real duplicate protection — the partial unique index uk_users_email (V18,
    // WHERE deleted=false) is what the database actually enforces; this just lets
    // UserCrudService give a readable message instead of a raw DB error. update() only
    // calls it when the email actually changes, or an account would collide with itself.
    boolean existsByEmailAndDeletedFalse(String email);

    // Counts live accounts carrying one role — the guard that lets RoleAdminService.delete
    // refuse a role still in use (roles are hard-deleted, and users.role_id is NOT NULL).
    // Also feeds the "userCount" column of the roles admin list. "DeletedFalse" matters: a
    // role held only by deleted accounts must still be deletable.
    long countByRoleIdAndDeletedFalse(Long roleId);
}

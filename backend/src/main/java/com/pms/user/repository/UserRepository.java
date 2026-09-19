package com.pms.user.repository;

import com.pms.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: users, the accounts people sign in with. A repository is
 * the only place in the application that talks to the database for that table. It carries
 * no business rule and no permission check; both live in the services above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> UserController   (/api/users, /api/me/context)
 *           -> UserCrudService  (@PreAuthorize("hasAuthority('MANAGE_USERS')")) or
 *              UserService      (the profile of the caller himself)
 *           -> UserRepository   (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table users
 * The User rows never leave the server: UserMapper (MapStruct) turns them into the
 * UserResponse record, and that record is what becomes JSON. That detail is a security
 * rule, not a style choice - a User carries the BCrypt password hash (BCrypt is a one-way
 * function: you can check a password against the hash, you cannot read the password back
 * out of it), and no mapper of this project ever copies that field.
 *
 * WHAT MAKES THIS FILE SPECIAL: IT IS THE ENTRY POINT OF AUTHENTICATION
 * findActiveByEmailWithRole, the first method below, is called on EVERY authenticated
 * request that is not already in the security cache, and on every login, refresh, logout
 * and password change. Its callers are spread over the whole application:
 *   * UserDetailsServiceImpl  - builds the Spring Security authority list at login;
 *   * JwtAuthenticationFilter - rebuilds that list on each request and compares the
 *                               token version (ADR-017);
 *   * AuthService             - login, refresh, logout, change password;
 *   * UserService             - /api/me/context, the permissions the Angular UI uses to
 *                               show or hide a menu;
 *   * ResourceService, MissionService, PlanChargeService, ChargeReelleService - they turn
 *                               the e-mail carried by the token into the caller's row;
 *   * DataInitializer, DemoDataSeeder, EnterpriseDataSeeder - "does this account already
 *                               exist?" before seeding.
 * The other methods serve the administration screens (UserCrudService), the role-delete
 * guard (RoleAdminService) and the assignee check of the agile board (BacklogItemService).
 *
 * WHY IT EXISTS - what would break if you deleted it
 * There would be no sign-in at all. More precisely, the methods below add three things
 * the built-in JpaRepository methods cannot do:
 *   1. they hide the rows flagged deleted = true (soft delete);
 *   2. the login query loads the whole chain user -> role -> permissions in ONE
 *      statement, which is exactly the authority list @PreAuthorize needs;
 *   3. they answer the two questions the admin screens ask - "is this e-mail already
 *      taken?" and "how many people still carry this role?" - without loading rows.
 *
 * SOFT DELETE, AND WHY IT SHAPES EVERY METHOD NAME HERE
 * Deleting a user means setting users.deleted = TRUE; the row stays for the audit trail.
 * Nothing in the mapping filters those rows automatically, so every query below says
 * "u.deleted = false" by hand. The consequence is written in the database too: V18
 * dropped the absolute constraint uk_users_email of V1 and replaced it with a PARTIAL
 * unique index, "CREATE UNIQUE INDEX uk_users_email ON users(email) WHERE deleted =
 * FALSE". Why partial: with an absolute UNIQUE, deleting jean@s2i.tn and re-creating the
 * same person would be refused with a duplicate-key error, while the administrator sees
 * no such account on screen.
 *
 * ONE WORD ON "deleted" VERSUS "active", BECAUSE THEY LOOK ALIKE AND ARE NOT
 *   deleted = the account is gone for the users; every query of this file hides it.
 *   active  = the account exists but is switched off. NO query of this file filters on
 *             it, on purpose: an administrator must still see a deactivated colleague in
 *             the list in order to reactivate him. Being switched off is enforced
 *             elsewhere - UserDetailsServiceImpl passes accountLocked(!active) to Spring
 *             Security, AuthService.login throws DisabledException, and
 *             UserCrudService.deactivate also calls user.revokeAllTokens(), which kills
 *             the tokens the person already holds (ADR-017).
 *
 * SECURITY - the two protections that are NOT written in this file
 * 1. Permission: @PreAuthorize("hasAuthority('MANAGE_USERS')") sits on the SERVICE
 *    methods of UserCrudService, never on the controller and never here. The code tests a
 *    permission code, never a role name (ADR-001), so an administrator can move a
 *    permission from one role to another while the application is running.
 * 2. Project scope (ADR-021): ProjectScopeInterceptor protects the URLs
 *    /api/projects/{id}/**. The user endpoints carry no project id, so the interceptor
 *    cannot help them - which is another reason the permission check above matters.
 * So a method of this file is never safe on its own: called from a new place without
 * going through a service, it would skip both checks and could list every account of the
 * company.
 */
// Nothing implements this interface by hand, and that is normal: at start-up Spring Data
// JPA reads the interfaces that extend JpaRepository, turns each @Query text below into a
// real SQL statement, and builds the implementation itself (a "proxy" object) which it
// hands to the services that asked for a UserRepository. No @Repository annotation is
// needed, because extending JpaRepository is already the signal Spring looks for.
// A useful side effect: the @Query texts are parsed at start-up, so a typo in one of them
// stops the application immediately instead of failing on the day a user opens that
// screen.
// The two types between < > are generics - they tell the proxy what to work on:
//   User = the entity, so the table read is users,
//   Long = the type of the @Id field (inherited from BaseEntity), so findById takes a
//          Long.
// Example of what these generics buy: findById(1L) gives back an Optional<User> already
// typed. Without them the method would return Object, every caller would need a cast, and
// a ClassCastException would be waiting at run time.
// JpaRepository also brings in, for free, save(), findById(), findAll(), count(),
// deleteById()... The services use save() constantly but never deleteById(): deleting an
// account means setting deleted = true, never erasing the row, because projects, team
// assignments, workload rows and audit columns all point at it.
public interface UserRepository extends JpaRepository<User, Long> {

    // WHAT: loads ONE live account from its e-mail, together with its role AND all the
    //       permissions of that role, in a single SQL statement. It gives back an
    //       Optional: a box that either holds the user or is empty.
    // WHY IT IS THE MOST IMPORTANT METHOD OF THE FILE: the set it brings back,
    //       user.getRole().getPermissions(), IS the authority list. UserDetailsServiceImpl
    //       and JwtAuthenticationFilter map every Permission.code of that set to a Spring
    //       Security authority, and @PreAuthorize("hasAuthority('X')") on the SERVICE
    //       methods tests exactly those strings (ADR-001 - the code never tests a role
    //       name).
    //
    //   (a) "JOIN FETCH u.role r" - a plain (inner) join, and that is correct here
    //       because users.role_id is NOT NULL (V1__schema_auth.sql): an account without a
    //       role cannot exist, so no row can be lost by the inner join. FETCH means "load
    //       the role in the same trip", not just "join to filter".
    //       WITHOUT the FETCH: User.role is mapped EAGER, so Hibernate would still load
    //       it - but with a SECOND round trip to PostgreSQL, on every single request.
    //
    //   (b) "LEFT JOIN FETCH r.permissions" - AND IT MUST STAY *LEFT*. A role can
    //       legitimately have no permission at all: that is the state of a role that has
    //       just been created from the admin screen, or whose matrix has just been
    //       emptied.
    //       WITHOUT the LEFT: a plain JOIN keeps only the rows that have a match, so such
    //       an account would not come back at all, and the sign-in would fail on
    //       "Identifiants incorrects" - a message that points at the password while the
    //       password is perfectly good. The person would be locked out with no way to
    //       understand why.
    //
    //   (c) "u.deleted = false" - soft delete, see the note in the header. WITHOUT IT, a
    //       deleted account could still sign in.
    //
    // WHY NO "AND u.active = true": see the header. An inactive account must still be
    //       LOADED, so that AuthService can answer "Compte desactive" instead of
    //       "Identifiants incorrects", and so that the administrator can reactivate it.
    //
    // WHY NO "DISTINCT" although a COLLECTION is fetched: joining the permissions
    //       multiplies the SQL rows (one user with eight permissions comes back as eight
    //       rows). Hibernate 6, which Spring Boot 3.3 uses, removes those duplicate
    //       parent rows by itself, so exactly one User object comes out and the Optional
    //       is valid. On the old Hibernate 5 behaviour the same query would have returned
    //       eight identical Users and Spring Data would have thrown
    //       IncorrectResultSizeDataAccessException - which is why you may see DISTINCT
    //       written in older code doing the same thing.
    //
    // WHY IT IS NOT A PERFORMANCE PROBLEM TO LOAD ALL OF THIS ON EVERY REQUEST: it is
    //       not loaded on every request. JwtAuthenticationFilter keeps the built
    //       authority list in the "securityContext" cache under the key
    //       email + ":" + tokenVersion, so this query runs only on a cache miss. The
    //       version is part of the key on purpose: bumping tokenVersion (logout, refresh
    //       rotation, password change, role change) makes every old key unreachable, so a
    //       revoked session can never be served from the cache (ADR-017).
    //
    // SPEED: V1 creates "CREATE INDEX idx_users_email ON users(email) WHERE deleted =
    //       FALSE", and V18 adds the partial UNIQUE index on the same column, so
    //       PostgreSQL jumps straight to the single row.
    //
    // ":email" is a named parameter matched to the Java argument of the same name. No
    // @Param annotation is needed because Spring Boot compiles with the -parameters flag,
    // which keeps the real argument names inside the .class file. And because the value
    // travels to PostgreSQL apart from the query text, it can never be read as SQL: that
    // is what blocks SQL injection - an e-mail typed as "' OR 1=1 --" is looked up as a
    // literal e-mail address and simply matches nothing.
    //
    // NULL ARGUMENT: ResourceService.currentUserId() can call this with a null e-mail
    // when there is no authenticated user. That is not a crash - the comparison
    // "u.email = null" is never true in SQL, so the Optional comes back empty and the
    // caller throws AccessDeniedException. Exactly the wanted behaviour.
    //
    // WHO CALLS IT: the full list is in the header of this file. It is the busiest query
    // of the authentication path.
    @Query("SELECT u FROM User u JOIN FETCH u.role r LEFT JOIN FETCH r.permissions WHERE u.email = :email AND u.deleted = false")
    Optional<User> findActiveByEmailWithRole(String email);

    // WHAT: every live account, with its role already loaded, sorted by last name then
    //       first name.
    // WHY "JOIN FETCH u.role": UserMapper reads role.getId() and role.getName() for EVERY
    //       row of the list. application.yml sets "open-in-view: false", so the database
    //       session is already closed when MapStruct builds the HTTP response.
    //       WITHOUT the fetch: User.role being EAGER, Hibernate would fire one extra
    //       SELECT per user while still inside the transaction - the classic "N+1
    //       selects" problem, 40 accounts meaning 41 queries for one screen.
    // WHY a plain JOIN and not LEFT: users.role_id is NOT NULL, so every user has a role
    //       and no row can be lost. A LEFT join here would only be a slower way to get
    //       the same rows.
    // WHY the permissions are NOT fetched here, unlike the login query above: this list
    //       feeds a table of names, roles and e-mails. Pulling the twenty permission rows
    //       of every role would multiply the SQL rows for data nobody displays.
    // WHY "ORDER BY u.lastName, u.firstName": people look for a colleague by last name,
    //       and the second key settles the two Ben Ali.
    //       WITHOUT any ORDER BY: PostgreSQL returns the rows in no guaranteed order and
    //       the list could reshuffle between two page loads.
    //
    // CAREFUL - this returns EVERY account of the company. It is safe only because both
    // callers are guarded on the service side.
    //
    // WHO CALLS IT: UserCrudService.findAll() (guarded by MANAGE_USERS) and
    // UserCrudService.findAssignable(), which is guarded by
    // hasAnyAuthority('ASSIGN_CHEF_PROJET','ASSIGN_DEVELOPER','MANAGE_USERS') - a
    // director or a project manager must be able to fill the "who do I put on this
    // project?" selector without holding the full user-administration permission.
    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.deleted = false ORDER BY u.lastName, u.firstName")
    List<User> findAllActive();

    // WHAT: reads ONE live account by its database id, as an Optional.
    // WHY not the inherited findById(id): findById ignores the soft-delete flag. It would
    //       let a card of the agile board be assigned to a colleague whose account was
    //       deleted last month, and that name would then appear on the board with no way
    //       to reach the person.
    // WHY Optional: it forces the caller to deal with the "not found" case instead of
    //       silently working with null. BacklogItemService writes
    //       .orElseThrow(() -> new NotFoundException(...)), which turns the empty box
    //       into a clean 404.
    // WHY there is NO "JOIN FETCH u.role" here, unlike the two methods above, and why
    //       that is not a mistake: the only caller needs the User to store it as the
    //       assignee of a backlog item, and never reads its role. User.role is mapped
    //       EAGER, so Hibernate loads it anyway with one extra SELECT, inside the
    //       transaction - correct, just not free. Adding the fetch here would be a small
    //       improvement, not a bug fix.
    // SPEED: no index is written by hand for this read, and none is needed. The column is
    //       declared "id BIGSERIAL PRIMARY KEY" (V1), so PostgreSQL builds a unique index
    //       on it by itself, jumps straight to that single row, and only then checks
    //       deleted = false on it.
    //
    // WHO CALLS IT: BacklogItemService.resolveAssignee, which asks two questions in a row
    // - "is this a real, non-deleted account?" (this method) and "is that person on THIS
    // project's team?" (a derived exists... query on TeamAssignmentRepository). Note that
    // UserCrudService does NOT use it: it has its own loadUser(id) written as
    // findById(...).filter(u -> !u.isDeleted()), which gives the same result in Java.
    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deleted = false")
    Optional<User> findActiveById(Long id);

    // WHAT: the same list as findAllActive, but one page at a time. Page<User> carries the
    //       rows of the page AND the total number of matching rows, which is what lets the
    //       screen print "page 2 of 7".
    // WHY a paged version exists next to the full list: a company that has run for years
    //       has hundreds of accounts. Sending them all in one response makes the answer
    //       heavy and the table slow to draw.
    //
    // WHY @Query is written here with TWO texts, value and countQuery, instead of one:
    //   * value is the query that reads the rows of the page;
    //   * countQuery is the query that counts the matching rows for the total.
    //   When countQuery is left out, Spring Data builds the count query itself by
    //   rewriting the SELECT clause of the first one - and the rewrite keeps the
    //   "JOIN FETCH" part, which makes no sense in a COUNT and which Hibernate refuses,
    //   because a fetch join has nothing to fetch into when only a number is selected.
    //   WITHOUT the hand-written countQuery: the application would fail on this query.
    //   Note that the count query drops the join completely: counting accounts does not
    //   need their roles, and asking PostgreSQL for that join would only make the count
    //   slower.
    //
    // WHY PAGING AND JOIN FETCH TOGETHER ARE SAFE HERE, which is not always true: the
    // fetched link is @ManyToOne, so one user always gives exactly one row and LIMIT /
    // OFFSET can be applied by the database. If a COLLECTION were fetched instead (say
    // r.permissions, as in the login query), one user would give several rows, LIMIT
    // would cut in the middle of a user, and Hibernate would fall back to reading
    // everything and paging in memory - fast on a demo, deadly on real data.
    //
    // WHERE THE SORT COMES FROM: there is no ORDER BY in the text. Spring Data appends
    // one from the Sort carried by the Pageable argument. UserController declares
    // @PageableDefault(size = 20, sort = "lastName", direction = ASC), so a request that
    // asks for nothing still gets a stable order. If that default were removed, the pages
    // would overlap and drop rows, because "the first 20 rows" has no meaning without an
    // order.
    //
    // WHO CALLS IT: UserCrudService.findAll(Pageable). The screen itself goes through
    // searchActive below, which does the same thing plus the filters.
    @Query(value = "SELECT u FROM User u JOIN FETCH u.role WHERE u.deleted = false",
           countQuery = "SELECT COUNT(u) FROM User u WHERE u.deleted = false")
    Page<User> findAllActivePaged(Pageable pageable);

    // WHAT: the paged user list of the administration screen, with three filters that can
    //       all be left out: a piece of text looked for in the first name, the last name
    //       or the e-mail; a role; and the on/off state of the account.
    // WHY ONE method with three optional filters instead of six methods: the screen has
    //       one search box, one role selector and one state selector, and the user may
    //       combine them in any way. Writing one query per combination would mean eight
    //       queries to keep in step.
    //
    // THE TEXT BLOCK (the """ ... """ form) is a Java 15 feature: a string written on
    // several lines without "+" and without \n. It changes nothing for the database; it
    // only makes a long JPQL query readable. JPQL looks like SQL but is written on the
    // Java classes: "User u" is the entity name, not the table name; Hibernate translates
    // it into real SQL.
    //
    // HOW EACH FILTER SWITCHES ITSELF OFF - read this pattern once, it is used three
    // times:
    //   (a) ":search = '' OR ...(the three LIKE tests)..." - when the caller sends an
    //       empty text, the first half is true and PostgreSQL never even looks at the
    //       LIKE tests, so every user passes. UserCrudService is what guarantees the
    //       empty string: it writes "String normalized = (search == null) ? '' :
    //       search.trim()". That is not decoration - a null parameter inside LOWER()
    //       makes PostgreSQL guess the type of the parameter, and it guesses "bytea",
    //       which fails with "function lower(bytea) does not exist". The empty string
    //       avoids the whole question.
    //   (b) ":roleId IS NULL OR r.id = :roleId" - no role chosen means the first half is
    //       true and the filter is off. Here a null IS expected and handled by the query
    //       itself, because a plain comparison to null is never true in SQL and would
    //       silently return zero rows.
    //   (c) ":active IS NULL OR u.active = :active" - same shape, on a Boolean. Note the
    //       type is Boolean and not boolean: only the object form can be null, and null
    //       is what "the user did not choose" means. With a primitive boolean the filter
    //       could never be switched off.
    //
    // THE THREE "LOWER(...) LIKE LOWER(CONCAT('%', :search, '%'))" TESTS: LOWER on both
    //       sides makes the search case-insensitive, and CONCAT('%', x, '%') puts the
    //       wildcards around the text so "mar" matches "Marwa" and also "Benmarzouk".
    //       WITHOUT the LOWER on both sides: typing "marwa" would not find "Marwa", and
    //       users would think the search box is broken.
    //       COST: a LIKE that starts with '%' cannot use a normal index, so PostgreSQL
    //       reads the table. On a few hundred accounts that is instant; it is the kind of
    //       thing that would need a trigram index on a table of millions of rows.
    //       SAFETY: the text is still a bound parameter, so it cannot inject SQL. It CAN
    //       contain a '%' typed by the user, which would then act as a wildcard - harmless
    //       here, it only widens the search.
    //
    // WHY THE COUNT QUERY IS NOT A COPY OF THE FIRST ONE - two differences, both on
    // purpose:
    //   * it drops "JOIN FETCH u.role r", because a fetch join has nothing to fetch into
    //     when only a number is selected and Hibernate refuses it (same reason as in
    //     findAllActivePaged above);
    //   * having dropped the alias r, it writes the role filter as "u.role.id = :roleId".
    //     That is not a second join: the id of a @ManyToOne link is already in the users
    //     table, in the foreign key column role_id, so Hibernate reads it without
    //     touching the roles table at all.
    //   The two texts MUST keep the same WHERE clause otherwise. If one of them ever
    //   loses a filter, the page would show 20 rows while claiming a different total, and
    //   the pagination at the bottom of the screen would lie.
    //
    // "u.deleted = false" is in BOTH texts for the reason given in the header: deleted
    // accounts must never appear, and must not be counted either.
    //
    // WHERE THE SORT COMES FROM: again, from the Pageable, with
    // @PageableDefault(sort = "lastName") in UserController.
    //
    // WHO CALLS IT: UserCrudService.search(...), which is what GET /api/users really runs,
    // guarded by @PreAuthorize("hasAuthority('MANAGE_USERS')").
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

    // WHAT: answers true when a live account already uses this e-mail, false otherwise.
    // WHY there is no @Query on this one: Spring Data reads the METHOD NAME and writes
    //       the query from it. "existsBy" + "Email" + "And" + "DeletedFalse" becomes
    //       "SELECT count(*) > 0 FROM users WHERE email = ? AND deleted = false". This is
    //       called a derived query. The advantage over a hand-written @Query: the name is
    //       checked against the entity at start-up, so renaming the field "email" in User
    //       makes the application refuse to start, instead of leaving a query that is
    //       wrong.
    // WHY "DeletedFalse" is part of the name: without it, an e-mail freed by a
    //       soft-deleted account would look taken forever, and a colleague who left and
    //       came back could never get his address again.
    // WHY exists and not find: nothing is read from the row, only its presence is tested.
    //       Loading the whole account - password hash included - to look at whether it is
    //       null would move far more data for the same answer.
    //
    // THIS CHECK IS NOT THE REAL PROTECTION, AND THAT IS WORTH SAYING OUT LOUD.
    // UserCrudService.create() calls this method, then saves. Between the two, another
    // request can insert the same address: two administrators creating jean@s2i.tn at the
    // same instant would both see "free". What really forbids the duplicate is the
    // partial unique index uk_users_email ON users(email) WHERE deleted = FALSE, created
    // by V18. The database refuses the second insert whatever the application does. The
    // role of this method is to give the administrator a clear message ("Email deja
    // utilise") instead of a raw database error.
    // Why that index is PARTIAL and not the plain UNIQUE constraint of V1: see the
    // soft-delete note in the header of this file.
    //
    // WHO CALLS IT: UserCrudService.create (always) and UserCrudService.update, but there
    // only when the address actually changes - the test is written
    // "!user.getEmail().equals(request.email()) && existsByEmailAndDeletedFalse(...)".
    // Without that first half, saving an account without touching its e-mail would make
    // it collide with itself, and the administrator could never fix a misspelt first
    // name.
    boolean existsByEmailAndDeletedFalse(String email);

    // WHAT: counts the live (non soft-deleted) accounts that carry one given role. It
    //       gives back a long, not a list.
    // WHY it exists at all - it is the guard that protects the role table: roles are
    //       really deleted from the database, not soft-deleted (RoleAdminService.delete
    //       calls roleRepository.delete). users.role_id is NOT NULL, so removing a role
    //       that people still carry would leave those rows pointing at nothing.
    //       WITHOUT this count: the delete would either be refused by the foreign key
    //       fk_users_role with an unreadable database error, or - if the constraint were
    //       ever written with a cascade - take the accounts down with it. Either way the
    //       administrator gets no useful message. With it, he reads "Ce role est affecte
    //       a 7 utilisateur(s) - reaffectez-les avant suppression".
    // WHY count and not findAllByRoleId(...).size(): PostgreSQL answers with one number.
    //       Loading seven hundred User objects into memory to call .size() on the list
    //       would move the whole table for a figure the database already knows.
    // WHY it is a derived query: "countBy" + "RoleId" + "And" + "DeletedFalse" becomes
    //       "SELECT count(*) FROM users WHERE role_id = ? AND deleted = false".
    //       "RoleId" is read as "the id of the role link", so Hibernate compares the
    //       foreign key column role_id directly and never joins the roles table.
    // WHY "DeletedFalse": a role carried only by accounts that were deleted is free, and
    //       must be deletable. Without it, one old deleted account would block the
    //       cleaning of an obsolete role forever.
    // SPEED: V1 creates "CREATE INDEX idx_users_role_id ON users(role_id)". Note it is
    //       NOT partial, unlike most indexes of this schema, so it covers the role_id
    //       side of this query and PostgreSQL only has to check deleted = false on the
    //       rows it finds.
    //
    // WHO CALLS IT: RoleAdminService twice - in delete(), as the guard described above,
    // and in toResponse(), to fill the "userCount" column the admin list shows next to
    // each role.
    long countByRoleIdAndDeletedFalse(Long roleId);
}

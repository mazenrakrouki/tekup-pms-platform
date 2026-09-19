package com.pms.user.repository;

import com.pms.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: roles. One row of that table is a named bundle of
 * permissions - ADMIN, DIRECTEUR, CHEF_PROJET, DEVELOPPEUR, plus any role an
 * administrator creates. A repository is the only place in the application that talks to
 * the database for that table. It carries no business rule and no permission check; both
 * live in the services above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> RoleController     (/api/admin/roles)
 *           -> RoleAdminService   (@PreAuthorize("hasAuthority('MANAGE_ROLES')"))
 *           -> RoleRepository     (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table roles
 * The Role rows never leave the server: RoleAdminService turns them into the RoleResponse
 * record, and that record is what becomes JSON.
 * Three other callers use it, and they explain why the two methods below exist:
 *   * UserCrudService turns the roleId sent with a new account into a real Role row (with
 *     the inherited findById), and UserController lists the roles for the drop-down of
 *     the user form;
 *   * PermissionAdminService reads every role with the inherited findAll() to answer
 *     "which roles hold this permission?";
 *   * the bootstrap and demo loaders (shared/config/DataInitializer, DemoDataSeeder,
 *     EnterpriseDataSeeder) look roles up BY NAME - see findByName below.
 *
 * WHERE THIS TABLE SITS IN THE AUTHORIZATION CHAIN (ADR-001)
 *   User  ->  Role  ->  Permission  ->  Spring Security authority  ->  @PreAuthorize
 * A person holds exactly one Role (users.role_id is NOT NULL); a Role holds a set of
 * Permissions through the join table role_permissions. UserDetailsServiceImpl and
 * JwtAuthenticationFilter turn every Permission.code of that set into an "authority"
 * string, and @PreAuthorize("hasAuthority('X')") on the SERVICE methods tests that
 * string.
 * READ THIS TWICE, because it is the question a jury asks: the role NAME is a label for
 * humans, never a security rule. No @PreAuthorize and no if-statement in this project
 * branches on "ADMIN" or "CHEF_PROJET". That is what makes the matrix dynamic - an
 * administrator moves a permission from one role to another from the admin screen, and
 * the application obeys immediately, without a recompile and without a redeploy.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * Four things at once: nobody could create an account (UserCrudService could not resolve
 * the roleId into a Role), the role-administration screens would disappear, the two demo
 * loaders could not attach their users to a role, and DataInitializer could not create
 * the very first ADMIN account - so a fresh database would have no way in at all.
 *
 * ONE DETAIL THAT SURPRISES PEOPLE: ROLES ARE HARD-DELETED
 * Almost every table of this project is soft-deleted (the row stays, a "deleted" flag is
 * raised). Roles are not: RoleAdminService.delete calls roleRepository.delete(role),
 * which really removes the row, and the matching lines of role_permissions go away with
 * it through "ON DELETE CASCADE" (V1__schema_auth.sql). That is safe because the service
 * refuses the delete twice over: a built-in role (roles.is_system = TRUE, set by V25) can
 * never be deleted, and a role still carried by at least one live user is refused too -
 * that second guard is UserRepository.countByRoleIdAndDeletedFalse. Without those two
 * guards, deleting a role would leave users pointing at a row that no longer exists,
 * while users.role_id is NOT NULL, so there would be no way back through the UI.
 *
 * SECURITY - the protection that is NOT written in this file
 * @PreAuthorize("hasAuthority('MANAGE_ROLES')") sits on the SERVICE methods of
 * RoleAdminService, never on the controller and never here. A method of this file is
 * therefore not safe on its own: called from a new place without going through a service,
 * it would skip that check - and this is the table that decides what everybody else is
 * allowed to do.
 */
// Nothing implements this interface by hand, and that is normal: at start-up Spring Data
// JPA reads the interfaces that extend JpaRepository and builds the implementation itself
// (a "proxy" object), which it hands to the services that asked for a RoleRepository. No
// @Repository annotation is needed, because extending JpaRepository is already the signal
// Spring looks for.
// The two types between < > are generics - they tell the proxy what to work on:
//   Role = the entity, so the table read is roles,
//   Long = the type of the @Id field (inherited from BaseEntity), so findById takes a
//          Long.
// Example of what these generics buy: findById(1L) gives back an Optional<Role> already
// typed. Without them the method would return Object, every caller would need a cast, and
// a ClassCastException would be waiting at run time.
// JpaRepository also brings in, for free, findAll(), findById(), save(), delete()... and
// every Role that comes out of any of them arrives with its permissions already loaded,
// because Role.permissions is mapped @ManyToMany(fetch = FetchType.EAGER). That is why
// there is no "findRoleWithPermissions" method here: it would be exactly findById.
public interface RoleRepository extends JpaRepository<Role, Long> {

    // WHAT: reads ONE role from its name ("ADMIN") instead of its database id. It gives
    //       back an Optional: a box that either holds the role or is empty.
    // WHY Optional and not the Role itself: it forces the caller to deal with the "no
    //       such role" case. DataInitializer uses the empty box as its question - "is the
    //       ADMIN role missing? then this is a brand new database, seed it" - and the two
    //       demo loaders write .orElse(null) and skip the users of a role they cannot
    //       find.
    //       WITHOUT IT: the method would return null, and the next
    //       User.builder().role(role) would store a null role on an account whose
    //       role_id column is NOT NULL, so the insert would fail with a database error
    //       instead of a readable message.
    // WHY there is no @Query on this one: Spring Data reads the METHOD NAME and writes
    //       the query from it. "findBy" + "Name" becomes
    //       "SELECT * FROM roles WHERE name = ?". This is called a derived query. The
    //       advantage over a hand-written @Query: the name is checked against the entity
    //       at start-up, so renaming the field "name" in Role makes the application
    //       refuse to start, instead of leaving a query that is wrong.
    // WHY it can return at most one row: V1__schema_auth.sql declares
    //       "CONSTRAINT uk_roles_name UNIQUE (name)".
    //       WITHOUT that constraint: a derived findBy... returning a single Optional
    //       would throw IncorrectResultSizeDataAccessException the day two rows shared a
    //       name - and two roles both called CHEF_PROJET would make "the project manager
    //       role" ambiguous, with two identical lines on screen carrying different
    //       permission sets.
    // WHY the name does NOT end with "AndDeletedFalse", unlike most read methods of this
    //       project: uk_roles_name is an ABSOLUTE unique constraint, it was never
    //       replaced by a partial index the way users.email was in V18, and roles are
    //       hard-deleted anyway (see the header), so in practice no roles row ever
    //       carries deleted = true. Filtering here would add a condition that can only
    //       hide a row the unique constraint still counts.
    // NOTE on what this method does NOT mean: reading a role by its NAME is a bootstrap
    //       convenience, not an authorization decision. ADR-001 forbids branching on the
    //       name, and the three callers below obey that - they are all creating data, not
    //       granting access.
    //
    // WHO CALLS IT: DataInitializer (twice - "does ADMIN exist?" at start-up, then to
    // attach each seeded account to its role) and the role(name) helper of DemoDataSeeder
    // and EnterpriseDataSeeder. Nothing in the request path of the application uses it.
    Optional<Role> findByName(String name);

    // WHAT: answers true when a role already carries this name, false otherwise.
    // WHY exists and not find: nothing is read from the row, only its presence is tested.
    //       Loading the whole role - and, because of the EAGER mapping, all of its
    //       permissions with it - just to look at whether it is null would move far more
    //       data for the same yes-or-no answer.
    // WHY it is a derived query too: "existsBy" + "Name" becomes
    //       "SELECT count(*) > 0 FROM roles WHERE name = ?", checked against the entity
    //       at start-up like findByName above.
    // WHY no "AndDeletedFalse": same reason as findByName - uk_roles_name covers every
    //       row of the table, deleted or not, so a check that ignored some rows would
    //       promise a name is free when the database will still refuse it.
    //
    // THIS CHECK IS NOT THE REAL PROTECTION, AND THAT IS WORTH SAYING OUT LOUD.
    // RoleAdminService.create() calls this method, then saves. Between the two, another
    // request can insert the same name: two administrators creating "AUDITEUR" at the
    // same instant would both see "free". What really forbids the duplicate is the
    // constraint uk_roles_name in the database, which refuses the second insert whatever
    // the application does. The role of this method is to give the administrator a clear
    // message ("Un role porte deja ce nom") instead of a raw database error.
    //
    // WHO CALLS IT: RoleAdminService.create (always) and RoleAdminService.update, but
    // there only when the name actually changes - the test is written
    // "!role.getName().equals(name) && existsByName(name)". Without that first half, a
    // role saved without touching its name would collide with itself and the
    // administrator could never edit its description or its permissions again.
    boolean existsByName(String name);
}

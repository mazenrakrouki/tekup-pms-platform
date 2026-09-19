package com.pms.user.repository;

import com.pms.user.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: permissions. One row of that table is one right the
 * application can test, written as a short code - MANAGE_USERS, VIEW_KPI, MANAGE_DI,
 * VIEW_RESOURCES... A repository is the only place in the application that talks to the
 * database for that table. It carries no business rule and no permission check; both live
 * in the services above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> PermissionController  (/api/admin/permissions)
 *           -> PermissionAdminService (@PreAuthorize("hasAuthority('MANAGE_ROLES')"))
 *           -> PermissionRepository   (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table permissions
 * The Permission rows never leave the server: PermissionAdminService copies four fields
 * into the PermissionResponse record, and that record is what becomes JSON.
 * There is a second caller, RoleAdminService: when an administrator saves the permission
 * list of a role, resolvePermissions() turns the ids sent by the browser into real
 * Permission rows with the inherited findAllById(...) before attaching them to the role.
 *
 * WHERE THIS TABLE SITS IN THE AUTHORIZATION CHAIN (ADR-001)
 *   User  ->  Role  ->  Permission  ->  Spring Security authority  ->  @PreAuthorize
 * A person holds exactly one Role; a Role holds a set of Permissions (join table
 * role_permissions); UserDetailsServiceImpl and JwtAuthenticationFilter turn every
 * Permission.code of that set into an "authority" string; and
 * @PreAuthorize("hasAuthority('X')") on the SERVICE methods tests that string.
 * The code of this project NEVER tests a role name. That is what makes the matrix
 * dynamic: an administrator can move MANAGE_DI from one role to another while the
 * application is running, and nothing has to be recompiled or redeployed.
 *
 * WHY THERE IS NO create / update / delete HERE, AND THAT IS ON PURPOSE
 * The catalogue of permission codes is defined by the CODE, not by the users. A new code
 * only has an effect the day a @PreAuthorize somewhere names it, so inventing a code from
 * an admin screen would create a right that guards nothing. New codes therefore arrive
 * through Flyway migrations (V1 and V2 seed the first ones, V13 adds VIEW_ALL_PROJECTS,
 * V23 adds MANAGE_DI, V25 restores MANAGE_ROLES, V27 adds the agile ones). What an
 * administrator really changes is the ASSIGNMENT of those codes to roles, and that is
 * RoleAdminService's job. PermissionAdminService is read-only for the same reason.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * RoleAdminService and PermissionAdminService would not compile, so the whole
 * role-administration module (the Roles and Permissions admin screens) would disappear.
 * The permission catalogue would still be readable through Role.getPermissions(), but
 * nothing could list the codes that exist, and nothing could attach a chosen set of codes
 * to a role.
 *
 * SECURITY - the protection that is NOT written in this file
 * @PreAuthorize("hasAuthority('MANAGE_ROLES')") sits on the SERVICE methods of
 * PermissionAdminService and RoleAdminService, never on the controller and never here. A
 * method of this file is therefore not safe on its own: called from a new place without
 * going through a service, it would skip that check and expose the whole security matrix.
 */
// Nothing implements this interface by hand, and that is normal: at start-up Spring Data
// JPA reads the interfaces that extend JpaRepository and builds the implementation itself
// (a "proxy" object), which it hands to the services that asked for a
// PermissionRepository. No @Repository annotation is needed, because extending
// JpaRepository is already the signal Spring looks for.
// The two types between < > are generics - they tell the proxy what to work on:
//   Permission = the entity, so the table read is permissions,
//   Long       = the type of the @Id field (inherited from BaseEntity), so findById takes
//                a Long.
// Example of what these generics buy: findById(1L) gives back an Optional<Permission>
// already typed. Without them the method would return Object, every caller would need a
// cast, and a ClassCastException would be waiting at run time.
// JpaRepository also brings in, for free, findAll(), findAllById(), findById(), save()...
// PermissionAdminService uses findAll() and RoleAdminService uses findAllById(); neither
// ever calls a delete method, because the catalogue belongs to the migrations.
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    // WHAT: reads ONE permission from its business code ("MANAGE_DI") instead of its
    //       database id. It gives back an Optional: a box that either holds the
    //       permission or is empty.
    // WHY Optional and not the Permission itself: it forces the caller to deal with the
    //       "no such code" case instead of silently working with null. The test fixtures
    //       write .orElseGet(() -> repo.save(...)), which turns the empty box into a
    //       freshly created row.
    //       WITHOUT IT: the method would return null, the next permission.getId() would
    //       throw a NullPointerException, and the failure would point at the wrong place.
    // WHY there is no @Query on this one: Spring Data reads the METHOD NAME and writes
    //       the query from it. "findBy" + "Code" becomes
    //       "SELECT * FROM permissions WHERE code = ?". This is called a derived query.
    //       The advantage over a hand-written @Query: the name is checked against the
    //       entity at start-up, so renaming the field "code" in Permission makes the
    //       application refuse to start, instead of leaving a query that is wrong.
    // WHY it can return at most one row: V1__schema_auth.sql declares
    //       "CONSTRAINT uk_permissions_code UNIQUE (code)".
    //       WITHOUT that constraint: a derived findBy... that returns a single Optional
    //       would throw IncorrectResultSizeDataAccessException the day two rows shared a
    //       code - and, worse, two rows called MANAGE_DI would mean a role could hold one
    //       of them and still be refused by the check that reads the other.
    // WHY the name does NOT end with "AndDeletedFalse", unlike most read methods of this
    //       project: uk_permissions_code is an ABSOLUTE unique constraint, it was never
    //       replaced by a partial index the way users.email was in V18. So a row flagged
    //       deleted = true still occupies its code in the table. A "get or create" helper
    //       that filtered the deleted rows out would see the code as free, try to insert
    //       it, and be refused by the database with a duplicate-key error. Answering
    //       "this code already exists" is exactly what the callers need.
    // SPEED: V1 also creates "CREATE INDEX idx_permissions_code ON permissions(code)
    //       WHERE deleted = FALSE", and the unique constraint has an index of its own, so
    //       PostgreSQL jumps straight to the row instead of reading the whole table.
    //
    // ":code" is bound as a parameter, so the value travels to PostgreSQL apart from the
    // query text and can never be read as SQL: that is what blocks SQL injection.
    //
    // WHO CALLS IT: no caller in the running application - say that plainly rather than
    // guessing. Its users are the integration tests: TestFixtures.perm(...) and
    // AgileControllerTest both do findByCode(code).orElseGet(() -> save(...)), so each
    // test can build a role carrying exactly the permissions that test needs, and can be
    // run twice in a row without creating a duplicate code.
    Optional<Permission> findByCode(String code);
}

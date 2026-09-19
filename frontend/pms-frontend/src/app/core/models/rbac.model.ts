/*
 * FILE: rbac.model.ts
 *
 * WHAT THIS FILE IS
 * The shapes of the authorization administration, seen from the browser. RBAC stands for
 * Role-Based Access Control: users hold roles, roles hold permissions, and the code only
 * ever tests permissions. This file describes a permission, a role, and the body sent when
 * a role is created or changed.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot RoleAdminService / PermissionAdminService answer with the Java records
 *   RoleResponse, PermissionResponse and PermissionWithRolesResponse
 *     -> Jackson turns them into JSON
 *     -> core/services/rbac.service.ts declares its Observables with the shapes below
 *     -> features/admin/roles/role-list.component.ts (create and edit a role) and
 *        features/admin/permissions/permission-list.component.ts (read-only catalogue)
 *        display them.
 *
 * WHY IT EXISTS - AND WHY IT IS THE HEART OF THE DYNAMIC AUTHORIZATION
 * The whole application is built so that no line of code ever tests a role NAME. Java
 * writes @PreAuthorize("hasAuthority('MANAGE_DI')") and Angular writes
 * hasPermission('MANAGE_DI'); neither of them knows that a role called CHEF_PROJET exists.
 * The link between the two lives in the database, and these shapes are how an
 * administrator edits that link from a screen.
 * That is what lets the company invent a new role tomorrow - say AUDITEUR, read-only on
 * everything - without one line of code being changed, recompiled or redeployed. Delete
 * this file and the roles would have to be edited straight in PostgreSQL.
 *
 * WHAT THIS FILE DOES NOT DO
 * It does not grant anything. It only describes the JSON of the administration screens.
 * The refusal is on the server: every public method of RoleAdminService and
 * PermissionAdminService carries @PreAuthorize("hasAuthority('MANAGE_ROLES')"). Hiding the
 * admin menu in the sidebar is comfort; refusing the call is security.
 */

/**
 * One permission, which is one thing a user may be allowed to do (mirror of the Java
 * record PermissionResponse).
 *
 * Permissions are NOT created from a screen. They exist because some @PreAuthorize in the
 * Java source names them, and they are inserted by a Flyway migration. Letting an
 * administrator invent a permission code would produce a row that no Java method ever
 * checks: a right that looks granted in the interface and protects nothing.
 */
export interface Permission {
  id: number;
  /**
   * The code the code itself tests, for example 'MANAGE_DI'. This exact text is what goes
   * inside hasAuthority(...) on the server and inside hasPermission(...) in Angular.
   * It is the real identifier of the permission; the id below is only the database key.
   */
  code: string;
  /**
   * Which part of the application the permission belongs to ("PROJECT", "BILLING"...).
   * It exists so the admin screens can GROUP the permissions instead of printing a flat
   * list of several dozen codes, which nobody could tick correctly.
   */
  module: string;
  /** A sentence in plain words explaining what the code allows, shown next to the tick box. */
  description?: string;
}

/**
 * A permission plus the names of the roles that currently hold it (mirror of the Java
 * record PermissionWithRolesResponse). Returned by GET /admin/permissions?withRoles=true
 * and used by the read-only permission catalogue.
 *
 * "extends Permission" means: everything a Permission has, plus roleNames. Why extends and
 * not a second full list of fields: the two shapes must stay in step. Written twice, a
 * field added to Permission tomorrow would be added to one of them and forgotten in the
 * other, and the catalogue would silently stop showing it.
 *
 * Why this second endpoint exists at all: it answers the question an auditor asks first -
 * "who can do this?" - which the role-by-role view cannot answer without opening every
 * role one after the other.
 */
export interface PermissionWithRoles extends Permission {
  /** Role NAMES, not ids: this list is only ever printed, never sent back. */
  roleNames: string[];
}

/**
 * One role as the server SENDS it (mirror of the Java record RoleResponse).
 * A role is a named bundle of permissions given to users.
 */
export interface Role {
  id: number;
  /** The name, written in UPPER_SNAKE_CASE ("CHEF_PROJET"). The server enforces that
   *  shape. Remember that no line of code decides anything by reading this name. */
  name: string;
  description?: string;
  /**
   * true for the roles the application ships with and depends on.
   * The server protects them: a system role cannot be renamed and cannot be deleted.
   * Why: without that rule an administrator could delete the role that holds MANAGE_ROLES,
   * and from that moment NOBODY could edit roles any more - the application would have
   * locked its own administration out, with no way back except editing the database by
   * hand.
   * The screen reads this flag to grey out the delete button, which is only the visible
   * half of the same rule.
   */
  system: boolean;
  /**
   * How many active users currently hold this role. Counted by the server on each read.
   * It is sent so the screen can warn before a deletion ("this role is used by 7 people"),
   * instead of letting an administrator discover afterwards that he has just taken their
   * rights away from a third of the company.
   */
  userCount: number;
  /**
   * The permissions of the role, as FULL objects and not just ids.
   * Why: the edit screen has to print the code, the module and the description of every
   * permission it ticks. With ids alone it would need a second request and would have to
   * join the two lists itself - and a permission missing from the second answer would be
   * drawn as an empty tick box with no label.
   */
  permissions: Permission[];
}

/**
 * The body SENT when a role is created or updated (mirror of the Java record RoleRequest).
 *
 * WHY IT IS NOT THE Role SHAPE ABOVE
 * What is sent is not what comes back. The answer carries the id, the system flag and the
 * user count, which the browser must never choose: a client able to send 'system: false'
 * could unlock the delete button on a role the application needs to work.
 */
export interface RoleRequest {
  /** UPPER_SNAKE_CASE, at most 50 characters. The server checks the shape and answers 400
   *  with a clear message when it does not match, so a role named "chef projet" is refused
   *  before it can sit in the list next to CHEF_PROJET and be confused with it. */
  name: string;
  description?: string;
  /**
   * The permissions of the role, sent as IDS and not as codes or as objects.
   * Why ids: the server looks each one up, so an id that does not exist is refused at
   * once. If the browser sent codes, a misspelled code could be stored as a permission
   * that nothing ever checks - a right that appears granted in the interface and protects
   * nothing.
   * It is the COMPLETE list, not a list of changes: whatever is not in it is removed from
   * the role. That is why the edit screen always sends every ticked box, not only the ones
   * the user has just touched.
   */
  permissionIds: number[];
}

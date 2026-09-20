// RBAC admin shapes: users hold roles, roles hold permissions, and the code only ever tests
// permissions (never a role name) — these interfaces are how an admin edits that link from
// a screen. Nothing here grants anything; the server enforces MANAGE_ROLES on every write.

/** One permission — a thing a user may be allowed to do (mirror of PermissionResponse).
 *  Not creatable from a screen: permissions exist because a @PreAuthorize in the Java source
 *  names them and a Flyway migration inserts the row. */
export interface Permission {
  id: number;
  /** The exact code tested by hasAuthority(...)/hasPermission(...) (e.g. 'MANAGE_DI'); the
   *  real identifier — id above is only the database key. */
  code: string;
  /** Which part of the app the permission belongs to ("PROJECT", "BILLING"...), used to
   *  group the admin screen's permission list. */
  module: string;
  /** A sentence in plain words explaining what the code allows, shown next to the tick box. */
  description?: string;
}

/** A permission plus the names of the roles holding it (mirror of PermissionWithRolesResponse),
 *  for the read-only catalogue answering "who can do this?" without opening every role. */
export interface PermissionWithRoles extends Permission {
  /** Role NAMES, not ids: this list is only ever printed, never sent back. */
  roleNames: string[];
}

/** One role as the server sends it (mirror of RoleResponse): a named bundle of permissions. */
export interface Role {
  id: number;
  /** UPPER_SNAKE_CASE name, enforced by the server. No code decides anything from this name. */
  name: string;
  description?: string;
  /** true for roles the application ships with and depends on; the server refuses to rename
   *  or delete them (e.g. deleting the role holding MANAGE_ROLES would lock out admin itself).
   *  The screen greys out the delete button from this flag. */
  system: boolean;
  /** Active users holding this role, counted server-side, so the screen can warn before a
   *  deletion ("used by 7 people"). */
  userCount: number;
  /** Full permission objects, not just ids, so the edit screen can print each one's code,
   *  module and description without a second request. */
  permissions: Permission[];
}

/** Body sent when a role is created or updated (mirror of RoleRequest). Smaller than Role:
 *  id, system and userCount are server-derived — a client sending 'system: false' must not
 *  be able to unlock the delete button on a role the app needs. */
export interface RoleRequest {
  /** UPPER_SNAKE_CASE, at most 50 characters; the server answers 400 on a mismatch. */
  name: string;
  description?: string;
  /** Permission IDS (not codes/objects), so an unknown one is rejected at once. This is the
   *  COMPLETE list — whatever isn't included is removed from the role. */
  permissionIds: number[];
}

import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Permission, PermissionWithRoles, Role, RoleRequest } from '../models/rbac.model';
import { environment } from '../../../environments/environment';

/**
 * WHAT THIS FILE IS
 * The HTTP client of the RBAC administration screens. RBAC means Role-Based Access Control:
 * a role is a named bag of permissions, and a permission is one capability such as
 * MANAGE_DI. This file knows the six addresses under /api/admin that read and write those
 * two tables. It draws no screen and keeps no state.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : features/admin/roles/role-list.component.ts (create, rename and re-arm a
 *                role) and features/admin/permissions/permission-list.component.ts (the
 *                read-only map of who holds what).
 * What it calls: HttpClient -> core/interceptors/auth.interceptor.ts (Authorization header
 *                and token renewal) -> Spring Boot RoleController and PermissionController
 *                -> RoleService and PermissionAdminService, which carry the real checks.
 * The typed answers come from core/models/rbac.model.ts.
 *
 * WHY IT EXISTS - AND WHY THE WHOLE FEATURE EXISTS
 * Authorization in this project is dynamic: the link between a role and its permissions is
 * rows in the database, not a list written in the Java code. This service is what makes
 * that promise real - without these screens, changing what a project manager may do would
 * mean an SQL script and a new deployment. With them it is a click, and the server drops
 * its cached authority lists right after the save (ADR-017), so the open sessions rebuild
 * their permissions from the new matrix instead of keeping the old one until they log out.
 * It is also why the code never tests a role NAME anywhere: names can be created and
 * renamed from this very screen, so only the permission codes are stable.
 *
 * SECURITY - READ THIS BEFORE THE JURY ASKS
 * These six calls are the most powerful of the application: whoever can write here can give
 * himself every other permission. The guard is MANAGE_ROLES, checked by @PreAuthorize on
 * the SERVICE methods of the server, never in this file. Hiding the admin menu is comfort
 * for the user, not security: the same URL answers 403 when called with curl.
 * Note that ADR-021 plays NO role here. These addresses carry no project number, so there
 * is no perimeter to check - a role belongs to the whole company, not to one project.
 */
// Client for the RBAC administration (roles and permissions). Guarded by MANAGE_ROLES on
// the backend.
// @Injectable lets Angular build this class and inject it; providedIn: 'root' creates ONE
// shared instance for the whole application, and lets the build drop the class from the
// bundle when nobody uses it - which is the normal case for a user without the admin screens.
@Injectable({ providedIn: 'root' })
export class RbacService {
  // inject() is the modern way to receive a dependency instead of writing a constructor.
  // readonly stops any code from replacing the HttpClient later by mistake.
  // Why not "new HttpClient()": we would lose the interceptor chain, so requests would leave
  // without the Authorization header and the server would answer 401 every time.
  private readonly http = inject(HttpClient);

  // The common start of the six URLs: ".../api" + "/admin".
  // Why it is built once from the environment file: the development build talks to localhost
  // and the production build to the real server, with no code change.
  private readonly base = `${environment.apiUrl}/admin`;

  /**
   * Reads every role with its permissions (GET /api/admin/roles).
   *
   * Observable<Role[]>: an observable is a value that will arrive later; nothing travels on
   * the network until somebody subscribes.
   * Each Role also carries 'system' and 'userCount'. They are there so the screen can protect
   * the user from himself: a system role must not be renamed or deleted, and deleting a role
   * that still has holders would leave those accounts with no permission at all.
   */
  listRoles(): Observable<Role[]> {
    // The <Role[]> between the angle brackets is a generic: it tells TypeScript the shape of
    // the JSON body. It is a compile-time promise only, nothing checks the real answer at
    // run time; it is safe because the shape is fixed by RoleResponse on the server.
    return this.http.get<Role[]>(`${this.base}/roles`);
  }

  /**
   * Creates a role (POST /api/admin/roles) and gives back the row as the server saved it.
   *
   * RoleRequest carries the name, the description and permissionIds: the ids of the
   * permissions the role must hold.
   * Why ids and not the permission codes: an id is what the join table stores, so the server
   * has nothing to translate and an unknown id is refused straight away. Sending codes would
   * mean a spelling mistake creates a role that is quietly missing one capability.
   */
  createRole(req: RoleRequest): Observable<Role> {
    return this.http.post<Role>(`${this.base}/roles`, req);
  }

  /**
   * Replaces one role (PUT /api/admin/roles/{id}) and gives back the updated row.
   *
   * Why PUT and not PATCH: permissionIds is the COMPLETE new list of what the role may do.
   * The server keeps exactly what is in that list, so a permission left out is a permission
   * taken away. This is the whole point - it is how a capability is removed from a role.
   * The consequence to have in mind: sending a partial list here silently strips the role.
   */
  updateRole(id: number, req: RoleRequest): Observable<Role> {
    return this.http.put<Role>(`${this.base}/roles/${id}`, req);
  }

  /**
   * Removes one role (DELETE /api/admin/roles/{id}).
   *
   * Observable<void>: the server answers 204 No Content, so there is no body to read.
   * The server refuses to delete a system role or a role that still has holders, and answers
   * an error the screen shows as a toast. Without that rule, deleting a role would leave real
   * accounts pointing at nothing, and those users could no longer do anything at all.
   */
  deleteRole(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/roles/${id}`);
  }

  /**
   * Reads the catalogue of permissions (GET /api/admin/permissions): every capability the
   * application knows, with its code and the module it belongs to.
   *
   * Why the list comes from the server and is not written in the TypeScript: the permissions
   * are rows created by the Flyway migrations. A hard-coded list in the browser would drift
   * the day a migration adds one, and the new capability would be impossible to grant from
   * the screen although the backend already checks it.
   */
  listPermissions(): Observable<Permission[]> {
    return this.http.get<Permission[]>(`${this.base}/permissions`);
  }

  /**
   * Same catalogue, but each permission also carries the names of the roles that hold it
   * (GET /api/admin/permissions?withRoles=true). This is the "who can do what" map.
   *
   * Why the same address with a flag instead of a second endpoint: it is the same list, only
   * richer. Two endpoints would mean two places to keep in step the day a permission field
   * is added.
   * Why the extra work is not done by default: computing the holders means joining the role
   * table for every row, and the create/edit form only needs the plain list. The flag lets
   * the heavy version be asked for only by the screen that displays it.
   */
  listPermissionsWithRoles(): Observable<PermissionWithRoles[]> {
    return this.http.get<PermissionWithRoles[]>(`${this.base}/permissions?withRoles=true`);
  }
}

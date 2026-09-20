import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Permission, PermissionWithRoles, Role, RoleRequest } from '../models/rbac.model';
import { environment } from '../../../environments/environment';

// HTTP client for RBAC administration: roles (named bags of permissions) and permissions
// (individual capabilities), the tables behind dynamic authorization — changing what a role
// can do is a click here instead of an SQL script and redeploy. Guarded by MANAGE_ROLES on
// the server (@PreAuthorize, not this file); ADR-021 doesn't apply since roles aren't project-scoped.
@Injectable({ providedIn: 'root' })
export class RbacService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin`;

  // 'system' and 'userCount' on each Role let the screen block renaming/deleting a system
  // role or a role that still has holders.
  listRoles(): Observable<Role[]> {
    return this.http.get<Role[]>(`${this.base}/roles`);
  }

  // permissionIds are ids, not codes, so the server rejects an unknown one outright instead of
  // silently creating a role missing a capability.
  createRole(req: RoleRequest): Observable<Role> {
    return this.http.post<Role>(`${this.base}/roles`, req);
  }

  // PUT: permissionIds is the complete new list, so an omitted id is a capability removed.
  updateRole(id: number, req: RoleRequest): Observable<Role> {
    return this.http.put<Role>(`${this.base}/roles/${id}`, req);
  }

  // Server refuses to delete a system role or one that still has holders.
  deleteRole(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/roles/${id}`);
  }

  // Sourced from the server (Flyway-migrated rows), not hardcoded, so it can't drift from
  // what the backend actually enforces.
  listPermissions(): Observable<Permission[]> {
    return this.http.get<Permission[]>(`${this.base}/permissions`);
  }

  // Same catalogue with each permission's holding roles attached — the "who can do what" map.
  // Behind a flag rather than always-on because computing holders joins the role table per row.
  listPermissionsWithRoles(): Observable<PermissionWithRoles[]> {
    return this.http.get<PermissionWithRoles[]>(`${this.base}/permissions?withRoles=true`);
  }
}

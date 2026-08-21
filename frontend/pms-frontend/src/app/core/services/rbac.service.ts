import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Permission, PermissionWithRoles, Role, RoleRequest } from '../models/rbac.model';
import { environment } from '../../../environments/environment';

/** Client API pour l'administration RBAC (rôles + permissions). Gardé par MANAGE_ROLES côté backend. */
@Injectable({ providedIn: 'root' })
export class RbacService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin`;

  listRoles(): Observable<Role[]> {
    return this.http.get<Role[]>(`${this.base}/roles`);
  }

  createRole(req: RoleRequest): Observable<Role> {
    return this.http.post<Role>(`${this.base}/roles`, req);
  }

  updateRole(id: number, req: RoleRequest): Observable<Role> {
    return this.http.put<Role>(`${this.base}/roles/${id}`, req);
  }

  deleteRole(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/roles/${id}`);
  }

  listPermissions(): Observable<Permission[]> {
    return this.http.get<Permission[]>(`${this.base}/permissions`);
  }

  listPermissionsWithRoles(): Observable<PermissionWithRoles[]> {
    return this.http.get<PermissionWithRoles[]>(`${this.base}/permissions?withRoles=true`);
  }
}

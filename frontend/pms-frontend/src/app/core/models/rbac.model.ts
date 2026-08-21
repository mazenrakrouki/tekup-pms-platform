export interface Permission {
  id: number;
  code: string;
  module: string;
  description?: string;
}

export interface PermissionWithRoles extends Permission {
  roleNames: string[];
}

export interface Role {
  id: number;
  name: string;
  description?: string;
  system: boolean;
  userCount: number;
  permissions: Permission[];
}

export interface RoleRequest {
  name: string;
  description?: string;
  permissionIds: number[];
}

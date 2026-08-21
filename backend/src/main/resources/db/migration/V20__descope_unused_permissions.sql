-- N-2: MANAGE_ROLES and VIEW_AUDIT_LOG are seeded but never enforced by any @PreAuthorize check.
-- The role-permission admin UI and audit-log viewer features are formally descoped for this release.
-- Remove the dead permissions to keep the RBAC matrix clean and avoid misleading the frontend.
DELETE FROM role_permissions WHERE permission_id IN (
    SELECT id FROM permissions WHERE code IN ('MANAGE_ROLES', 'VIEW_AUDIT_LOG')
);
DELETE FROM permissions WHERE code IN ('MANAGE_ROLES', 'VIEW_AUDIT_LOG');

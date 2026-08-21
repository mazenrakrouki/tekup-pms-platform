-- V24: Grant MANAGE_RESOURCES to DIRECTEUR so they can view and modify TCC rates.
-- PM (CHEF_PROJET) keeps VIEW_RESOURCES (read-only). Developer has no access (unchanged).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'DIRECTEUR' AND p.code = 'MANAGE_RESOURCES'
ON CONFLICT DO NOTHING;

-- V24: grants MANAGE_RESOURCES to DIRECTEUR so they can view and edit TCC rates
-- (PM keeps read-only VIEW_RESOURCES). New file rather than editing V2/V23:
-- Flyway checksums applied migrations, so a change of mind is always a new version.

-- Cross join filtered to the one (role, permission) pair wanted; ids come from
-- BIGSERIAL so they can't be hardcoded as literals.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'DIRECTEUR' AND p.code = 'MANAGE_RESOURCES'
-- Grant may already exist; DO NOTHING avoids aborting on replay.
ON CONFLICT DO NOTHING;

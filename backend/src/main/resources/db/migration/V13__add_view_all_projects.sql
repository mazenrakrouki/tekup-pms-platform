-- V13: adds VIEW_ALL_PROJECTS, the capability that lifts the perimeter filter (ADR-021), granted to Director
-- by default. The PM and developer keep their perimeter derived from real relationships (ProjectScopeService),
-- never from a role-name check (ADR-001): this is the scope axis, separate from the permission (what) axis.

-- Creates the capability itself, grouped under 'PROJET' for the admin screen. ON CONFLICT guards a replay.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
    ('VIEW_ALL_PROJECTS', 'PROJET', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- DIRECTEUR : full portfolio access
-- Only the director, who is accountable for the portfolio as a whole; giving the PM or developer this row
-- would silently switch off ADR-021's perimeter check for them. A default, not a law - reassignable via V25.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'VIEW_ALL_PROJECTS'
WHERE r.name = 'DIRECTEUR'
ON CONFLICT DO NOTHING;

-- Invalidate the Director sessions so the new permission set is reloaded (ADR-017)
-- Without this, an already signed-in director would keep the cached authority list until it expired and would
-- see only his own projects, which would look like a bug. Only directors are bumped, the only role this file changes.
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name = 'DIRECTEUR');

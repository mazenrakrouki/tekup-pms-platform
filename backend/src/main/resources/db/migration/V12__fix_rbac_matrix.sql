-- V12: rebuilds the default RBAC matrix (Audit Phase C, docs/AUTHORIZATION_MATRIX.md).
-- The V2/V9/V10/V11 grants broke business boundaries (BR-038/BR-050, ADR-005); this migration is pure data
-- (ADR-001) - it rewrites role_permissions to match the target matrix and forces every user to log in again.

-- 1. Purge the grants of the four business roles (deterministic rebuild)
-- Delete-then-insert rather than targeted statements, so the result depends only on this file, not on what
-- V2/V9/V10/V11 happened to leave behind. Without this step, step 2's ON CONFLICT would only add permissions
-- and every wrong V2 grant (admin's VIEW_KPI, director's MANAGE_BILLING...) would survive untouched.
DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('ADMIN','DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));

-- 2. Re-insert, matching the target matrix
-- Each block below is a cross join filtered to one role and a list of permission codes (no FK equality
-- needed), giving exactly one row per code. ON CONFLICT DO NOTHING guards a replay against the PK.

-- ── ADMIN : platform administration + TCC reference data only (5) ──────
-- TCC ("taux de cout charge") is a person's loaded-cost multiplier, held on "resources" rows, reached via
-- MANAGE_RESOURCES/VIEW_RESOURCES. The administrator no longer holds VIEW_PROJECT/VIEW_KPI or anything
-- financial (BR-050): he administers accounts, roles and the rate table, not project content.
-- MANAGE_ROLES is granted here, removed by V20, restored by V25 once the Roles screen exists.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'MANAGE_USERS','MANAGE_ROLES','VIEW_AUDIT_LOG',
    'MANAGE_RESOURCES','VIEW_RESOURCES'
) WHERE r.name = 'ADMIN'
ON CONFLICT DO NOTHING;

-- ── DIRECTEUR : portfolio governance - project management + read-only views (13) ──
-- The director steers the portfolio (creates projects, sets budgets, appoints the PM) and reads every
-- operational module; day-to-day work is delegated to the project manager.
-- Compared with V2 he loses MANAGE_BILLING (BR-038: reads a payment plan but doesn't invoice) and
-- MANAGE_RESOURCES (the rate table belongs to the administrator).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_RESOURCES',
    'VIEW_PROJECT','CREATE_PROJECT','EDIT_PROJECT','DELETE_PROJECT','ASSIGN_CHEF_PROJET',
    'ASSIGN_DEVELOPER','VIEW_TEAM',          -- the director may staff a team too (ADR-005 / D4)
    'VIEW_WORKLOAD',
    'VIEW_BILLING',                          -- read only: invoicing is the project manager's job (BR-038)
    'VIEW_MISSION',
    'VIEW_GOVERNANCE',
    'VIEW_KPI'
) WHERE r.name = 'DIRECTEUR'
ON CONFLICT DO NOTHING;

-- ── CHEF_PROJET : day-to-day running of the projects he manages (14) ──
-- Holds every MANAGE_ of the operational modules (billing, missions, governance) but not CREATE/DELETE_PROJECT:
-- he runs projects, he doesn't open or close them.
-- This list says WHAT he may do, never ON WHICH project - ProjectScopeInterceptor enforces the perimeter on
-- every /api/projects/{id}/** URL (ADR-021), so MANAGE_BILLING alone can't invoice a colleague's project.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_RESOURCES',
    'VIEW_PROJECT','EDIT_PROJECT',
    'ASSIGN_DEVELOPER','VIEW_TEAM',
    'VALIDATE_WORKLOAD','VIEW_WORKLOAD',     -- planning + validation both gated by VALIDATE_WORKLOAD (see AUTHORIZATION_MATRIX.md)
    'MANAGE_BILLING','VIEW_BILLING',
    'MANAGE_MISSION','VIEW_MISSION',
    'MANAGE_GOVERNANCE','VIEW_GOVERNANCE',
    'VIEW_KPI'
) WHERE r.name = 'CHEF_PROJET'
ON CONFLICT DO NOTHING;

-- ── DEVELOPPEUR : his own work only (5) - NOTHING financial (BR-050) ──
-- SUBMIT_WORKLOAD lets him declare his days; VIEW_WORKLOAD/VIEW_MISSION are narrowed to his own rows by the
-- repositories, not by this list. Loses the VIEW_KPI that V2 gave him (KPI screens carry budget/margin,
-- BR-050), and is deliberately not given VIEW_GOVERNANCE either, unlike the V11 grant.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','VIEW_TEAM',
    'SUBMIT_WORKLOAD','VIEW_WORKLOAD',
    'VIEW_MISSION'
) WHERE r.name = 'DEVELOPPEUR'
ON CONFLICT DO NOTHING;

-- 3. Raise token_version to kill every live session at once (ADR-017)
-- JwtAuthenticationFilter compares the token's "tokenVersion" claim against this column and refuses a stale
-- token; bumping it here forces a re-login so nobody keeps a withdrawn permission (e.g. a director who could
-- otherwise still invoice a client with a token signed before this migration).
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('ADMIN','DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));

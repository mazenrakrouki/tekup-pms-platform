-- V2: initial data — roles, permissions, first role/permission matrix.
-- V1 created the empty tables; this seeds the four roles, the permission
-- catalogue, and the first grants (later rebuilt by V12).
--
-- Runs once on a fresh database, which is why it uses plain INSERTs with no
-- "does it exist?" guard — later migrations that may replay add ON CONFLICT.
-- READ THIS BEFORE A JURY QUESTION: the matrix below is NOT what's in force
-- today. The live matrix is V12's rebuild, plus V13, V20 and V25.
--
-- ADR-001: the matrix is data, not Java — granting/revoking is an INSERT or
-- DELETE here, never a rebuild of the backend.

-- -- Roles -------------------------------------------------------
-- V25 later marks these four is_system = TRUE so they can't be renamed/deleted.
-- Names stay French: labels the company uses, never compared by security code.
INSERT INTO roles (name, created_at, updated_at, deleted) VALUES
    ('ADMIN',        NOW(), NOW(), FALSE),
    ('DIRECTEUR',    NOW(), NOW(), FALSE),
    ('CHEF_PROJET',  NOW(), NOW(), FALSE),
    ('DEVELOPPEUR',  NOW(), NOW(), FALSE);

-- -- Permissions -------------------------------------------------
-- Each code is the exact string some @PreAuthorize compares; a typo just
-- creates a right nothing will ever match. Second column groups by module.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
    -- Platform administration.
    -- MANAGE_ROLES / VIEW_AUDIT_LOG: unenforced at the time, removed by V20;
    -- V25 restores MANAGE_ROLES once the admin endpoints exist.
    ('MANAGE_USERS',        'ADMIN',     NOW(), NOW(), FALSE),
    ('MANAGE_ROLES',        'ADMIN',     NOW(), NOW(), FALSE),
    ('VIEW_AUDIT_LOG',      'ADMIN',     NOW(), NOW(), FALSE),
    -- Projects. ADR-021: holding VIEW_PROJECT/EDIT_PROJECT is not "on every
    -- project" — ProjectScopeInterceptor also checks the perimeter per id.
    -- VIEW_ALL_PROJECTS (which lifts that filter) doesn't exist yet — V13 adds it.
    ('VIEW_PROJECT',        'PROJET',    NOW(), NOW(), FALSE),
    ('CREATE_PROJECT',      'PROJET',    NOW(), NOW(), FALSE),
    ('EDIT_PROJECT',        'PROJET',    NOW(), NOW(), FALSE),
    ('DELETE_PROJECT',      'PROJET',    NOW(), NOW(), FALSE),
    ('ASSIGN_CHEF_PROJET',  'PROJET',    NOW(), NOW(), FALSE),
    -- Teams.
    ('ASSIGN_DEVELOPER',    'EQUIPE',    NOW(), NOW(), FALSE),
    ('VIEW_TEAM',           'EQUIPE',    NOW(), NOW(), FALSE),
    -- Workload: submitting your own days, validating someone else's, and
    -- viewing are three different trust levels.
    ('SUBMIT_WORKLOAD',     'CHARGE',    NOW(), NOW(), FALSE),
    ('VALIDATE_WORKLOAD',   'CHARGE',    NOW(), NOW(), FALSE),
    ('VIEW_WORKLOAD',       'CHARGE',    NOW(), NOW(), FALSE),
    -- Billing. V9 re-inserts these two codes under module BILLING but with
    -- ON CONFLICT DO NOTHING, so this FACTURATION module wins; V9 only adds grants.
    ('VIEW_BILLING',        'FACTURATION', NOW(), NOW(), FALSE),
    ('MANAGE_BILLING',      'FACTURATION', NOW(), NOW(), FALSE),
    ('VIEW_KPI',            'KPI',       NOW(), NOW(), FALSE),
    -- Resources: daily rates and TCC coefficients (V3) — cost data, so its own
    -- pair of codes rather than riding on VIEW_TEAM.
    ('MANAGE_RESOURCES',    'RESSOURCE', NOW(), NOW(), FALSE),
    ('VIEW_RESOURCES',      'RESSOURCE', NOW(), NOW(), FALSE);

-- -- Role to Permission mapping ----------------------------------
-- INSERT ... SELECT rather than literal ids: BIGSERIAL ids aren't known at
-- write time, so grants are looked up by name/code instead.

-- ADMIN: everything that exists at this point (cross join, no code list to
-- maintain). V12 later judges this too wide and cuts ADMIN down to five codes.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'ADMIN';

-- DIRECTEUR: portfolio governance. "JOIN ... ON p.code IN (...)" has no real
-- join condition between roles/permissions — it's a cross join filtered by
-- the code list, pairing the one role with each named permission.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','CREATE_PROJECT','EDIT_PROJECT','ASSIGN_CHEF_PROJET',
    'VIEW_TEAM','VIEW_BILLING','MANAGE_BILLING','VIEW_KPI',
    'VIEW_WORKLOAD','VIEW_RESOURCES','MANAGE_RESOURCES'
) WHERE r.name = 'DIRECTEUR';

-- CHEF_PROJET: runs the projects he leads day to day. No CREATE/DELETE_PROJECT
-- — he doesn't decide a project exists. EDIT_PROJECT only bites within his
-- ADR-021 perimeter (chef de projet or team member, never his role name).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','EDIT_PROJECT',
    'ASSIGN_DEVELOPER','VIEW_TEAM',
    'VALIDATE_WORKLOAD','VIEW_WORKLOAD',
    'VIEW_BILLING','VIEW_KPI','VIEW_RESOURCES'
) WHERE r.name = 'CHEF_PROJET';

-- DEVELOPPEUR: own work only. SUBMIT but not VALIDATE — he can't approve his
-- own days. No billing code (BR-050). VIEW_KPI is given here but removed by
-- V12 (replaced with VIEW_MISSION) since the KPI screen shows costs/margins.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','VIEW_TEAM',
    'SUBMIT_WORKLOAD','VIEW_WORKLOAD',
    'VIEW_KPI'
) WHERE r.name = 'DEVELOPPEUR';

-- No user row is created here: accounts are built at start-up by
-- DataInitializer (com.pms.shared.config), which BCrypt-hashes the password
-- so it's never stored readable. Disable via pms.demo.seed-users=false.

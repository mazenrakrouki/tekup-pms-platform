-- =============================================================
-- V12 : Correcting the default RBAC matrix
--       (original French title: "Correction de la matrice RBAC par defaut")
-- -------------------------------------------------------------
-- Audit Phase C (docs/AUTHORIZATION_MATRIX.md). The first matrix - V2
-- ("ADMIN gets everything") plus the grants added by V9, V10 and V11 - broke
-- the business boundaries of the company (BUSINESS_ANALYSIS section 3,
-- BR-038 / BR-050, ADR-005).
--
-- ADR-001 principle: the fix is PURELY DATA - not one line of backend or
-- frontend code changes. The mapping of the four business roles is rebuilt
-- deterministically so that it matches the target matrix of section 4 EXACTLY.
-- =============================================================
-- WHAT THIS FILE IS
--   One Flyway migration that contains no CREATE TABLE at all. It only rewrites
--   rows of role_permissions, the table that says which permission each role
--   holds, and then forces every user to log in again.
--
-- WHERE IT SITS IN THE FLOW
--   Before it: V1 created roles / permissions / role_permissions, V2 seeded the
--   first matrix, and V9, V10 and V11 each added their own grants. After it:
--   V13 adds VIEW_ALL_PROJECTS, V20 removes unused permissions, V24 and V25
--   adjust the matrix again. So this file is a step, not the final word - the
--   live matrix is "V12 + V13 + V20 + V24 + V25".
--   At runtime nothing calls this file. What reads its result is
--   JwtAuthenticationFilter: on every request it loads the user with his role
--   and his permissions and turns each permission CODE into a Spring Security
--   authority, which @PreAuthorize("hasAuthority('X')") on the services then
--   tests.
--
-- WHY IT EXISTS - AND WHY A MIGRATION RATHER THAN A CODE CHANGE
--   This is the payoff of ADR-001. Because no Java or TypeScript file ever asks
--   "is this user a DIRECTEUR?", a whole authorization policy can be corrected
--   by moving rows in a table. Delete this migration and the application still
--   runs, but an administrator would keep full access to every financial figure
--   of every project (BR-050), a director could still invoice a client
--   (BR-038), and a developer would still see the margin of his project.
--
-- HOW TO READ IT: three steps, in this order.
--   1. delete every grant of the four business roles;
--   2. insert the target set, one block per role;
--   3. raise token_version so nobody keeps the old rights in a live session.
-- =============================================================

-- 1. Purge the grants of the four business roles (deterministic rebuild)
-- WHAT: removes every row of role_permissions that belongs to one of the four
--       roles, so that step 2 starts from an empty slate.
-- WHY DELETE-THEN-INSERT rather than a list of targeted DELETEs and INSERTs:
--       the result then depends only on THIS file, never on what V2, V9, V10 and
--       V11 happened to leave behind. Run it on any database and the matrix ends
--       up identical. A list of targeted statements would have to be kept in step
--       with the history of every earlier migration for ever.
-- WHY THE SUB-QUERY "SELECT id FROM roles WHERE name IN (...)": roles.id is
--       filled by a sequence, so the numbers are not known when this file is
--       written. The roles are found by name, and only their ids are used.
-- WHY THE FOUR NAMES ARE LISTED rather than deleting everything: a role created
--       later by an administrator through the Roles screen must keep its own
--       grants. This purge is limited to the four roles the audit is about.
-- WITHOUT THIS STEP: step 2 would only ADD permissions (its ON CONFLICT clause
--       skips duplicates), so every wrong grant of V2 - the administrator's
--       VIEW_KPI, the director's MANAGE_BILLING, the developer's VIEW_KPI -
--       would survive, and the correction would do nothing at all.
DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('ADMIN','DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));

-- 2. Re-insert, matching the target matrix
-- The four blocks below all have the same shape:
--   FROM roles r JOIN permissions p ON p.code IN (...) WHERE r.name = '...'
-- The ON clause carries no equality between the two tables, so this is really a
-- cross join filtered twice: one role on one side, the listed permission codes
-- on the other, which produces exactly one row per code. It is the shortest way
-- to write "give this role this list" without knowing a single id.
-- ON CONFLICT DO NOTHING protects a replay: (role_id, permission_id) is the
-- primary key of role_permissions, so a duplicate pair would abort the whole
-- migration instead of being ignored.

-- ── ADMIN : platform administration + TCC reference data only (5) ──────
-- TCC ("taux de cout charge") is the loaded-cost rate of a person: the
-- multiplier applied to a daily rate to get the real cost to the company. It
-- lives on the "resources" rows, which is why it is reached through
-- MANAGE_RESOURCES / VIEW_RESOURCES.
-- WHAT CHANGES HERE, and expect the jury to ask: the administrator no longer
-- holds VIEW_PROJECT, VIEW_KPI or anything financial. He administers accounts,
-- roles and the rate reference table; he cannot open a project. That is the
-- audit decision (BR-050: the administrator sees no project financials).
-- Note that MANAGE_ROLES is granted here, is removed again by V20, and is
-- restored by V25 once the Roles screen really exists.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'MANAGE_USERS','MANAGE_ROLES','VIEW_AUDIT_LOG',
    'MANAGE_RESOURCES','VIEW_RESOURCES'
) WHERE r.name = 'ADMIN'
ON CONFLICT DO NOTHING;

-- ── DIRECTEUR : portfolio governance - project management + read-only views (13) ──
-- The director steers the portfolio: he creates projects, sets budgets, appoints
-- the project manager, and READS every operational module. He does not run the
-- daily work, which is delegated to the project manager.
-- Compared with V2 he LOSES MANAGE_BILLING (he may read a payment plan but not
-- invoice, BR-038) and MANAGE_RESOURCES (the rate reference table belongs to the
-- administrator).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_RESOURCES',
    'VIEW_PROJECT','CREATE_PROJECT','EDIT_PROJECT','DELETE_PROJECT','ASSIGN_CHEF_PROJET',
    'ASSIGN_DEVELOPER','VIEW_TEAM',          -- ASSIGN_DEVELOPER : the director may staff a team too (ADR-005 / D4)
    'VIEW_WORKLOAD',
    'VIEW_BILLING',                          -- read only: invoicing is the project manager's job (BR-038)
    'VIEW_MISSION',
    'VIEW_GOVERNANCE',
    'VIEW_KPI'
) WHERE r.name = 'DIRECTEUR'
ON CONFLICT DO NOTHING;

-- ── CHEF_PROJET : day-to-day running of the projects he manages (14) ──
-- The project manager holds every MANAGE_ of the operational modules - billing,
-- missions, governance - but not CREATE_PROJECT or DELETE_PROJECT: he runs
-- projects, he does not open or close them.
-- KEY POINT FOR THE DEFENCE: this list says WHAT he may do, never ON WHICH
-- PROJECT. The perimeter is a separate layer: ProjectScopeInterceptor checks,
-- on every URL matching /api/projects/{id}/**, that the caller really belongs to
-- that project (ADR-021). Holding MANAGE_BILLING does not let a project manager
-- invoice a colleague's project.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_RESOURCES',
    'VIEW_PROJECT','EDIT_PROJECT',
    'ASSIGN_DEVELOPER','VIEW_TEAM',
    'VALIDATE_WORKLOAD','VIEW_WORKLOAD',     -- planning + validation: both are gated by VALIDATE_WORKLOAD (see the coarsening note in AUTHORIZATION_MATRIX.md)
    'MANAGE_BILLING','VIEW_BILLING',
    'MANAGE_MISSION','VIEW_MISSION',
    'MANAGE_GOVERNANCE','VIEW_GOVERNANCE',
    'VIEW_KPI'
) WHERE r.name = 'CHEF_PROJET'
ON CONFLICT DO NOTHING;

-- ── DEVELOPPEUR : his own work only (5) - NOTHING financial (BR-050) ──
-- SUBMIT_WORKLOAD lets him declare his days; VIEW_WORKLOAD and VIEW_MISSION are
-- narrowed to his own rows by the repositories, not by this list.
-- He LOSES the VIEW_KPI that V2 had given him: KPI screens carry budget, margin
-- and consumption, and BR-050 walls a developer off from every financial figure.
-- He is deliberately NOT given VIEW_GOVERNANCE here either, although V11 had
-- granted it - the target matrix of the audit does not include it.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','VIEW_TEAM',
    'SUBMIT_WORKLOAD','VIEW_WORKLOAD',
    'VIEW_MISSION'
) WHERE r.name = 'DEVELOPPEUR'
ON CONFLICT DO NOTHING;

-- 3. Raise token_version to kill every live session at once (ADR-017)
-- WHAT: adds 1 to users.token_version for every user holding one of the four
--       roles.
-- HOW IT WORKS: the access token (the signed ticket the browser sends on every
--       call) carries a "tokenVersion" claim - a value written inside the token
--       when it was signed. JwtAuthenticationFilter compares that claim with
--       this column and refuses the request when the two differ. It also uses
--       "email:version" as its cache key, so the cached permission set of the
--       old session can never be reused by the new one.
-- WHY IT IS NEEDED HERE: the permissions were just rewritten, but the tokens
--       already handed out were signed before that. Without this bump, a
--       director who logged in five minutes ago would keep MANAGE_BILLING until
--       his token expired - the exact rights this migration was written to take
--       away. Concretely: he could still invoice a client after the company
--       decided he must not.
-- SIDE EFFECT THAT IS ACCEPTED ON PURPOSE: everyone is logged out. A policy
--       change is rare, and being asked to sign in again is a small price for
--       being certain that nobody keeps a withdrawn right.
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('ADMIN','DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));

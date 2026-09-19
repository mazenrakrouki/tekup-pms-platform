-- =============================================================
-- V13 : Portfolio-wide access capability (scope, ADR-021)
--       (original French title: "Capacite d'acces portefeuille")
-- -------------------------------------------------------------
-- Introduces VIEW_ALL_PROJECTS: the capability that lifts the perimeter filter
-- (full portfolio access). Granted to the Director by default.
-- The Project Manager and the Developer do NOT have this capability: their
-- perimeter is derived from real relationships (being the project manager of a
-- project, or being assigned to its team) by ProjectScopeService.
-- In line with ADR-001: driven by a capability, never by a role name.
-- =============================================================
-- WHAT THIS FILE IS
--   One Flyway migration, data only: it adds one row to "permissions" and one
--   row to "role_permissions", then forces the directors to log in again.
--
-- WHERE IT SITS IN THE FLOW
--   Before it: V12 rebuilt the whole role/permission matrix. After it: V20, V24
--   and V25 adjust the matrix again.
--   At runtime the code that reads this row is ProjectScopeService:
--     public static final String ALL_ACCESS = "VIEW_ALL_PROJECTS";
--     hasAllAccess()  -> does the caller hold that authority?
--   and it is used in two places:
--     * ProjectScopeInterceptor, on every URL matching /api/projects/{id}/**,
--       to decide whether the project id in the path has to be inside the
--       caller's perimeter (ADR-021);
--     * the repositories, to decide between "the whole portfolio" and
--       "only the ids of accessibleProjectIds()".
--
-- WHY IT EXISTS - THE HARD POINT OF THE DESIGN
--   PMS has TWO independent questions on every project URL:
--     1. WHAT may you do?   -> the permission (VIEW_PROJECT, MANAGE_BILLING...)
--     2. ON WHICH projects?  -> the scope (this file)
--   ADR-021 says both must pass. A project manager holds MANAGE_BILLING but his
--   scope covers only the projects he runs, so he cannot invoice a colleague's
--   project. A director holds VIEW_BILLING and, thanks to this row, a scope
--   with no limit, so he can read the payment plan of every project - and still
--   cannot invoice any of them, because he does not hold MANAGE_BILLING.
--   Without this second axis, "seeing the whole portfolio" could only have been
--   written as "if the user is a DIRECTEUR", which is exactly the role-name test
--   that ADR-001 forbids - and a fifth role created tomorrow through the Roles
--   screen could never be given portfolio access without a code change.
--
-- HOW THE OTHERS GET THEIR PERIMETER
--   ProjectRepository.findAccessibleProjectIdsByEmail() returns the ids of the
--   live, non-archived projects where the caller is the project manager OR is an
--   active member of the team. Note the consequence: an archived project is
--   never in that set, so only the holder of VIEW_ALL_PROJECTS can open one.
-- =============================================================

-- WHAT: creates the capability itself. It is a row, like every other permission
--       (ADR-001), grouped under the 'PROJET' module label on the Permissions
--       administration screen.
-- ON CONFLICT (code) DO NOTHING leans on uk_permissions_code (V1): if the code
--       is already there the row is skipped instead of aborting the migration,
--       so the script is safe to replay.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
    ('VIEW_ALL_PROJECTS', 'PROJET', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- DIRECTEUR : full portfolio access
-- WHAT: gives the new capability to one role only.
-- HOW : the JOIN carries no equality between roles and permissions, so this is a
--       cross join narrowed to one role and one permission code - exactly one
--       row. The ids come from sequences and cannot be written by hand.
-- WHY ONLY THE DIRECTOR: he is accountable for the portfolio as a whole. The
--       project manager and the developer are meant to work inside a perimeter,
--       and giving them this row would silently switch off ADR-021 for them.
-- NOTE: this is a default, not a law. Because the grant is data, an
--       administrator can hand the same capability to another role from the
--       Roles screen (V25) with no deployment at all.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'VIEW_ALL_PROJECTS'
WHERE r.name = 'DIRECTEUR'
ON CONFLICT DO NOTHING;

-- Invalidate the Director sessions so the new permission set is reloaded (ADR-017)
-- WHAT: adds 1 to users.token_version for every director.
-- HOW IT WORKS: the access token carries a "tokenVersion" claim - a value copied
--       into the signed token when it was issued. JwtAuthenticationFilter
--       compares that claim with this column and refuses the request when they
--       differ; it also keys its permission cache on "email:version", so the
--       entry built for the old session cannot be reused.
-- WHY IT IS NEEDED: the permissions are rebuilt from the database on each
--       request, but only on a cache miss. A director already signed in would
--       otherwise keep the cached authority list from before this migration and
--       would not receive VIEW_ALL_PROJECTS until his session expired - he would
--       still see only the projects he is personally attached to, and would call
--       it a bug.
-- Only the directors are touched here, because they are the only role this
-- migration changes. V12 had to bump all four roles for the same reason.
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name = 'DIRECTEUR');

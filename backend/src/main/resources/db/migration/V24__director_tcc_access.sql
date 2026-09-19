-- =============================================================
-- V24 : Grant MANAGE_RESOURCES to DIRECTEUR so they can view and modify TCC rates.
-- PM (CHEF_PROJET) keeps VIEW_RESOURCES (read-only). Developer has no access (unchanged).
-- -------------------------------------------------------------
-- WHAT THIS FILE IS
-- A Flyway migration (Flyway plays each .sql file of this folder once, in
-- version order, and records that it did). This one changes no table at all: it
-- adds a single row to the RBAC matrix.
--
-- TCC = "taux de coût complet", the fully loaded cost of one day of a person
-- (salary plus all charges). It is the number every margin in the application
-- is built on, so who may change it is a security question, not a comfort one.
--
-- WHERE IT SITS IN THE FLOW
--   The row written here is read by JwtAuthenticationFilter, which loads the
--   permission codes of the caller's role from the database and hands them to
--   Spring Security as authorities. The check itself lives on the service:
--   @PreAuthorize("hasAuthority('MANAGE_RESOURCES')") on the resource and TCC
--   methods. Nothing in the Java code tests a role NAME — that is exactly why a
--   change of rights is one INSERT here and not a code change.
--
-- WHY IT EXISTS
-- Before this file the Directeur could open the TCC reference table but not
-- correct a rate, so a wrong cost price had to go through an administrator.
-- Delete this file and the Directeur gets 403 on every write to the resources
-- and TCC screens.
--
-- WHY IT IS A NEW FILE AND NOT AN EDIT OF V2 OR V12
-- Flyway stores a checksum of every migration it has already run. Editing an
-- old file changes that checksum and Flyway then refuses to start against any
-- database where the old version was applied. A change of mind is always a new
-- number.
-- =============================================================

-- "FROM roles r, permissions p" is a CROSS JOIN written the old way: it pairs
-- every role with every permission, and the WHERE keeps the single pair
-- (DIRECTEUR, MANAGE_RESOURCES). Same result as the explicit JOIN used in V23.
-- Why a SELECT instead of literal ids: the ids come from BIGSERIAL counters and
-- are different in every database. Writing "VALUES (2, 17)" here would grant a
-- random permission to a random role on a freshly created database.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'DIRECTEUR' AND p.code = 'MANAGE_RESOURCES'
-- (role_id, permission_id) is the primary key of role_permissions, so DO NOTHING
-- covers the only possible clash: the grant already being there. Why it matters:
-- without it, replaying this file would abort on a duplicate key.
ON CONFLICT DO NOTHING;

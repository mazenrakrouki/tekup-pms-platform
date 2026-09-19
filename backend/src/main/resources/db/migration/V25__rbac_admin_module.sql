-- =============================================================
-- V25 : RBAC administration module (Roles + Permissions management)
-- -------------------------------------------------------------
-- Re-enables role/permission administration that was formally descoped in V20.
-- ADR-001 (dynamic RBAC): the matrix stays data-driven; this migration adds the
-- metadata the admin UI needs (human-readable descriptions, a system-role guard)
-- and restores the MANAGE_ROLES permission that gates the new endpoints.
--
-- WHAT THIS FILE IS
-- A Flyway migration (Flyway plays each .sql file of this folder once, in
-- version order, and records that it did). It does three different jobs:
--   (a) it adds the columns the admin screens need on roles and permissions,
--   (b) it fills those columns with text for the rows that already exist,
--   (c) it puts the MANAGE_ROLES permission back and grants it to ADMIN.
--
-- WHERE IT SITS IN THE FLOW
--   V1  created roles, permissions and role_permissions (RBAC = Role Based
--       Access Control: a role holds permissions, a user holds one role).
--   V2  seeded the four roles and the first permissions.
--   V20 deleted MANAGE_ROLES and VIEW_AUDIT_LOG, because at that time no
--       @PreAuthorize check used them — a permission nobody enforces is a lie
--       told to the frontend.
--   V25 (this file) puts MANAGE_ROLES back, now that the screens really exist.
--   Java side: RoleController / PermissionController expose the endpoints,
--   RoleAdminService carries @PreAuthorize("hasAuthority('MANAGE_ROLES')") on
--   its methods (the check sits on the SERVICE, never on the controller), and
--   com.pms.user.entity.Role maps the two new columns.
--
-- WHY IT EXISTS
-- Delete this file and the admin screens have no permission to enforce, so the
-- endpoints would be unreachable; the list of roles would show no explanation of
-- what each role is for; and nothing would stop an administrator from deleting
-- the CHEF_PROJET role, which every project row points at.
--
-- WHY A NEW FILE RATHER THAN EDITING V20
-- Flyway stores a checksum of each migration it has applied. Editing an applied
-- file changes that checksum, and Flyway then refuses to start against any
-- database that already ran the old text. A change of mind is always a new
-- number, which is also why the history of the decision stays readable here.
-- =============================================================

-- 1. Schema enrichment -----------------------------------------------------
-- Two pieces of metadata the admin UI needs, and nothing else: the tables
-- themselves keep the shape V1 gave them.
-- IF NOT EXISTS makes each statement replayable; without it, a migration
-- replayed by hand after a partial restore stops on "column already exists".
-- description is nullable: a role created tomorrow from the UI may have none,
-- and an empty explanation is better than a forced placeholder.
ALTER TABLE roles       ADD COLUMN IF NOT EXISTS description VARCHAR(255);
-- is_system is NOT NULL DEFAULT FALSE: every role that already exists becomes
-- "not a system role" immediately, and step 2 below then raises the flag for the
-- four built-in ones. Why a default is required here: without it, PostgreSQL
-- cannot add a NOT NULL column to a table that already holds rows.
-- What the flag buys: RoleAdminService refuses to rename or delete a role whose
-- is_system is TRUE. Without it, an administrator could delete CHEF_PROJET, and
-- every project row and every permission grant attached to it would go with it.
ALTER TABLE roles       ADD COLUMN IF NOT EXISTS is_system   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE permissions ADD COLUMN IF NOT EXISTS description VARCHAR(255);

-- 2. Protect the four built-in business roles from deletion / rename -------
-- These four are the roles the whole application is designed around. They are
-- matched by name and not by id, because ids come from a BIGSERIAL counter and
-- differ from one database to another.
UPDATE roles SET is_system = TRUE
WHERE name IN ('ADMIN', 'DIRECTEUR', 'CHEF_PROJET', 'DEVELOPPEUR');

-- 3. Human-readable role descriptions --------------------------------------
-- The text stays in French on purpose: it is DATA shown as-is in a French user
-- interface, not a comment. Translating it would change what the screen prints.
-- "AND description IS NULL" is the important half of each line: it only fills a
-- description that is still empty. Why: if an administrator has already edited
-- the wording from the UI, replaying this migration must not overwrite his text.
-- The doubled apostrophe in strings such as 'd''audit' below is how SQL escapes
-- a single quote inside a single-quoted string; one quote would end the string
-- early and turn the rest of the line into a syntax error.
UPDATE roles SET description = 'Administration de la plateforme : utilisateurs, rôles et référentiel TCC.'          WHERE name = 'ADMIN'       AND description IS NULL;
UPDATE roles SET description = 'Gouvernance du portefeuille : pilotage projets, vues transverses, TCC.'             WHERE name = 'DIRECTEUR'   AND description IS NULL;
UPDATE roles SET description = 'Exécution opérationnelle des projets gérés : équipe, charges, facturation.'         WHERE name = 'CHEF_PROJET' AND description IS NULL;
UPDATE roles SET description = 'Contributeur : consultation de ses projets et saisie de ses charges.'               WHERE name = 'DEVELOPPEUR' AND description IS NULL;

-- 4. Restore MANAGE_ROLES (deleted in V20) — now backed by real endpoints ---
-- This is the permission that gates RoleController and PermissionController. It
-- is re-created rather than un-deleted because V20 removed the row for good.
-- Module 'ADMIN' is only the grouping the admin UI uses to lay the permission
-- list out in sections.
INSERT INTO permissions (code, module, description, created_at, updated_at, deleted)
VALUES ('MANAGE_ROLES', 'ADMIN', 'Créer, modifier et supprimer les rôles ; affecter les permissions.', NOW(), NOW(), FALSE)
-- ON CONFLICT (code) targets the unique constraint on permissions.code
-- (uk_permissions_code, V1): if the code is already present, skip instead of
-- failing. Without it, a replay would abort here and steps 5 to 7 would never
-- run, leaving ADMIN without the grant he needs.
ON CONFLICT (code) DO NOTHING;

-- 5. Human-readable permission descriptions --------------------------------
-- One line per permission code, same rule as step 3: French text because it is
-- displayed as-is, and "AND description IS NULL" so an edit made from the UI is
-- never overwritten.
-- Matching on the code and not on an id is what makes this list survive any
-- database: the codes are the stable contract, the ids are not.
-- Note on the VIEW_AUDIT_LOG line: that permission was deleted by V20 and no
-- migration puts it back, so on a database built from V1 upwards this statement
-- matches zero rows and changes nothing. It is harmless, and it is ready for the
-- day the audit-log screen is delivered and the permission is re-created.
UPDATE permissions SET description = 'Gérer les comptes utilisateurs (création, activation, suppression).'  WHERE code = 'MANAGE_USERS'       AND description IS NULL;
UPDATE permissions SET description = 'Consulter le journal d''audit.'                                        WHERE code = 'VIEW_AUDIT_LOG'     AND description IS NULL;
UPDATE permissions SET description = 'Consulter les projets.'                                                WHERE code = 'VIEW_PROJECT'       AND description IS NULL;
UPDATE permissions SET description = 'Créer un projet.'                                                      WHERE code = 'CREATE_PROJECT'     AND description IS NULL;
UPDATE permissions SET description = 'Modifier un projet.'                                                   WHERE code = 'EDIT_PROJECT'       AND description IS NULL;
UPDATE permissions SET description = 'Supprimer (archiver) un projet.'                                       WHERE code = 'DELETE_PROJECT'     AND description IS NULL;
UPDATE permissions SET description = 'Affecter un chef de projet.'                                           WHERE code = 'ASSIGN_CHEF_PROJET' AND description IS NULL;
UPDATE permissions SET description = 'Affecter des développeurs à une équipe projet.'                        WHERE code = 'ASSIGN_DEVELOPER'   AND description IS NULL;
UPDATE permissions SET description = 'Consulter la composition des équipes.'                                 WHERE code = 'VIEW_TEAM'          AND description IS NULL;
UPDATE permissions SET description = 'Saisir ses propres charges de travail.'                                WHERE code = 'SUBMIT_WORKLOAD'    AND description IS NULL;
UPDATE permissions SET description = 'Valider les charges de travail de l''équipe.'                          WHERE code = 'VALIDATE_WORKLOAD'  AND description IS NULL;
UPDATE permissions SET description = 'Consulter les plans et charges réelles.'                               WHERE code = 'VIEW_WORKLOAD'      AND description IS NULL;
UPDATE permissions SET description = 'Consulter la facturation.'                                             WHERE code = 'VIEW_BILLING'       AND description IS NULL;
UPDATE permissions SET description = 'Gérer les jalons et la facturation.'                                   WHERE code = 'MANAGE_BILLING'     AND description IS NULL;
UPDATE permissions SET description = 'Consulter les indicateurs (KPI / EVM).'                                WHERE code = 'VIEW_KPI'           AND description IS NULL;
UPDATE permissions SET description = 'Consulter et modifier le référentiel des ressources et tarifs TCC.'    WHERE code = 'MANAGE_RESOURCES'   AND description IS NULL;
UPDATE permissions SET description = 'Consulter le référentiel des ressources et tarifs TCC.'                WHERE code = 'VIEW_RESOURCES'     AND description IS NULL;
UPDATE permissions SET description = 'Gérer les missions.'                                                   WHERE code = 'MANAGE_MISSION'     AND description IS NULL;
UPDATE permissions SET description = 'Consulter les missions.'                                               WHERE code = 'VIEW_MISSION'       AND description IS NULL;
UPDATE permissions SET description = 'Gérer la gouvernance (risques, livrables, changements).'               WHERE code = 'MANAGE_GOVERNANCE'  AND description IS NULL;
UPDATE permissions SET description = 'Consulter la gouvernance (risques, livrables, changements).'           WHERE code = 'VIEW_GOVERNANCE'    AND description IS NULL;
UPDATE permissions SET description = 'Gérer le devis interne (données financières sensibles).'               WHERE code = 'MANAGE_DI'          AND description IS NULL;
UPDATE permissions SET description = 'Consulter tous les projets du portefeuille.'                           WHERE code = 'VIEW_ALL_PROJECTS'  AND description IS NULL;

-- 6. Grant MANAGE_ROLES to ADMIN (the only role that administers RBAC) ------
-- "FROM roles r, permissions p" is a CROSS JOIN written the old way: it pairs
-- every role with every permission, and the WHERE keeps the one pair wanted.
-- ADMIN alone gets it on purpose: giving MANAGE_ROLES to any other role would
-- let that role grant itself every other permission, which is the classic way a
-- permission system is defeated from the inside.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'ADMIN' AND p.code = 'MANAGE_ROLES'
-- (role_id, permission_id) is the primary key of role_permissions, so DO NOTHING
-- covers the only possible clash: the grant already existing.
ON CONFLICT DO NOTHING;

-- 7. Invalidate ADMIN sessions so the new authority is picked up (ADR-017) --
-- Every access token carries a "tokenVersion" claim (a claim is one named value
-- written inside the token). JwtAuthenticationFilter compares that claim with
-- users.token_version on each request, and the ready-made authorities are cached
-- under the key "<e-mail>:<tokenVersion>". Adding 1 here makes every token signed
-- before this migration stale, so the very next request rebuilds the authority
-- list from the database and sees MANAGE_ROLES.
-- Without this line, an administrator who was already logged in would keep the
-- cached authority list, get 403 on the brand-new screens, and would have to log
-- out and back in — which looks exactly like a broken feature.
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name = 'ADMIN');

-- =============================================================
-- V25 : RBAC administration module (Roles + Permissions management)
-- -------------------------------------------------------------
-- Re-enables role/permission administration that was formally descoped in V20.
-- ADR-001 (dynamic RBAC): the matrix stays data-driven; this migration adds the
-- metadata the admin UI needs (human-readable descriptions, a system-role guard)
-- and restores the MANAGE_ROLES permission that gates the new endpoints.
-- =============================================================

-- 1. Schema enrichment -----------------------------------------------------
ALTER TABLE roles       ADD COLUMN IF NOT EXISTS description VARCHAR(255);
ALTER TABLE roles       ADD COLUMN IF NOT EXISTS is_system   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE permissions ADD COLUMN IF NOT EXISTS description VARCHAR(255);

-- 2. Protect the four built-in business roles from deletion / rename -------
UPDATE roles SET is_system = TRUE
WHERE name IN ('ADMIN', 'DIRECTEUR', 'CHEF_PROJET', 'DEVELOPPEUR');

-- 3. Human-readable role descriptions --------------------------------------
UPDATE roles SET description = 'Administration de la plateforme : utilisateurs, rôles et référentiel TCC.'          WHERE name = 'ADMIN'       AND description IS NULL;
UPDATE roles SET description = 'Gouvernance du portefeuille : pilotage projets, vues transverses, TCC.'             WHERE name = 'DIRECTEUR'   AND description IS NULL;
UPDATE roles SET description = 'Exécution opérationnelle des projets gérés : équipe, charges, facturation.'         WHERE name = 'CHEF_PROJET' AND description IS NULL;
UPDATE roles SET description = 'Contributeur : consultation de ses projets et saisie de ses charges.'               WHERE name = 'DEVELOPPEUR' AND description IS NULL;

-- 4. Restore MANAGE_ROLES (deleted in V20) — now backed by real endpoints ---
INSERT INTO permissions (code, module, description, created_at, updated_at, deleted)
VALUES ('MANAGE_ROLES', 'ADMIN', 'Créer, modifier et supprimer les rôles ; affecter les permissions.', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- 5. Human-readable permission descriptions --------------------------------
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
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'ADMIN' AND p.code = 'MANAGE_ROLES'
ON CONFLICT DO NOTHING;

-- 7. Invalidate ADMIN sessions so the new authority is picked up (ADR-017) --
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name = 'ADMIN');

-- =============================================================
-- V12 : Correction de la matrice RBAC par défaut
-- -------------------------------------------------------------
-- Audit Phase C (docs/AUTHORIZATION_MATRIX.md). La matrice initiale
-- (V2 "ADMIN : tout" + ajouts V9/V10/V11) violait les frontières métier
-- (BUSINESS_ANALYSIS §3, BR-038/BR-050, ADR-005).
--
-- Principe ADR-001 : la correction est PUREMENT DONNÉES — aucun
-- changement de code backend/frontend. On reconstruit déterministiquement
-- le mapping des 4 rôles pour qu'il corresponde EXACTEMENT à la cible §4.
-- =============================================================

-- 1. Purge des grants des 4 rôles métier (rebuild déterministe)
DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('ADMIN','DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));

-- 2. Réinsertion conforme à la matrice cible

-- ── ADMIN : administration plateforme + TCC uniquement (5) ──────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'MANAGE_USERS','MANAGE_ROLES','VIEW_AUDIT_LOG',
    'MANAGE_RESOURCES','VIEW_RESOURCES'
) WHERE r.name = 'ADMIN'
ON CONFLICT DO NOTHING;

-- ── DIRECTEUR : gouvernance portefeuille — gestion projet + vues (13) ──
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_RESOURCES',
    'VIEW_PROJECT','CREATE_PROJECT','EDIT_PROJECT','DELETE_PROJECT','ASSIGN_CHEF_PROJET',
    'ASSIGN_DEVELOPER','VIEW_TEAM',          -- ASSIGN_DEVELOPER : ADR-005 / D4
    'VIEW_WORKLOAD',
    'VIEW_BILLING',                          -- vue seule : facturation = PM (BR-038)
    'VIEW_MISSION',
    'VIEW_GOVERNANCE',
    'VIEW_KPI'
) WHERE r.name = 'DIRECTEUR'
ON CONFLICT DO NOTHING;

-- ── CHEF_PROJET : exécution opérationnelle des projets gérés (14) ──
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_RESOURCES',
    'VIEW_PROJECT','EDIT_PROJECT',
    'ASSIGN_DEVELOPER','VIEW_TEAM',
    'VALIDATE_WORKLOAD','VIEW_WORKLOAD',     -- planification + validation (cf. note coarsening)
    'MANAGE_BILLING','VIEW_BILLING',
    'MANAGE_MISSION','VIEW_MISSION',
    'MANAGE_GOVERNANCE','VIEW_GOVERNANCE',
    'VIEW_KPI'
) WHERE r.name = 'CHEF_PROJET'
ON CONFLICT DO NOTHING;

-- ── DEVELOPPEUR : son propre travail uniquement (5) — AUCUN financier (BR-050) ──
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','VIEW_TEAM',
    'SUBMIT_WORKLOAD','VIEW_WORKLOAD',
    'VIEW_MISSION'
) WHERE r.name = 'DEVELOPPEUR'
ON CONFLICT DO NOTHING;

-- 3. Bump token_version pour invalider immédiatement les sessions en cours (ADR-017)
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('ADMIN','DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));

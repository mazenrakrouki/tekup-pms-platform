-- =============================================================
-- V2 : Données initiales — Rôles, Permissions, Admin par défaut
-- =============================================================

-- ── Rôles ─────────────────────────────────────────────────────
INSERT INTO roles (name, created_at, updated_at, deleted) VALUES
    ('ADMIN',        NOW(), NOW(), FALSE),
    ('DIRECTEUR',    NOW(), NOW(), FALSE),
    ('CHEF_PROJET',  NOW(), NOW(), FALSE),
    ('DEVELOPPEUR',  NOW(), NOW(), FALSE);

-- ── Permissions ───────────────────────────────────────────────
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
    -- Administration
    ('MANAGE_USERS',        'ADMIN',     NOW(), NOW(), FALSE),
    ('MANAGE_ROLES',        'ADMIN',     NOW(), NOW(), FALSE),
    ('VIEW_AUDIT_LOG',      'ADMIN',     NOW(), NOW(), FALSE),
    -- Projets
    ('VIEW_PROJECT',        'PROJET',    NOW(), NOW(), FALSE),
    ('CREATE_PROJECT',      'PROJET',    NOW(), NOW(), FALSE),
    ('EDIT_PROJECT',        'PROJET',    NOW(), NOW(), FALSE),
    ('DELETE_PROJECT',      'PROJET',    NOW(), NOW(), FALSE),
    ('ASSIGN_CHEF_PROJET',  'PROJET',    NOW(), NOW(), FALSE),
    -- Équipes
    ('ASSIGN_DEVELOPER',    'EQUIPE',    NOW(), NOW(), FALSE),
    ('VIEW_TEAM',           'EQUIPE',    NOW(), NOW(), FALSE),
    -- Charges
    ('SUBMIT_WORKLOAD',     'CHARGE',    NOW(), NOW(), FALSE),
    ('VALIDATE_WORKLOAD',   'CHARGE',    NOW(), NOW(), FALSE),
    ('VIEW_WORKLOAD',       'CHARGE',    NOW(), NOW(), FALSE),
    -- Facturation
    ('VIEW_BILLING',        'FACTURATION', NOW(), NOW(), FALSE),
    ('MANAGE_BILLING',      'FACTURATION', NOW(), NOW(), FALSE),
    -- KPI
    ('VIEW_KPI',            'KPI',       NOW(), NOW(), FALSE),
    -- Ressources
    ('MANAGE_RESOURCES',    'RESSOURCE', NOW(), NOW(), FALSE),
    ('VIEW_RESOURCES',      'RESSOURCE', NOW(), NOW(), FALSE);

-- ── Mapping Rôle → Permission ──────────────────────────────────
-- ADMIN : tout
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'ADMIN';

-- DIRECTEUR
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','CREATE_PROJECT','EDIT_PROJECT','ASSIGN_CHEF_PROJET',
    'VIEW_TEAM','VIEW_BILLING','MANAGE_BILLING','VIEW_KPI',
    'VIEW_WORKLOAD','VIEW_RESOURCES','MANAGE_RESOURCES'
) WHERE r.name = 'DIRECTEUR';

-- CHEF_PROJET
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','EDIT_PROJECT',
    'ASSIGN_DEVELOPER','VIEW_TEAM',
    'VALIDATE_WORKLOAD','VIEW_WORKLOAD',
    'VIEW_BILLING','VIEW_KPI','VIEW_RESOURCES'
) WHERE r.name = 'CHEF_PROJET';

-- DEVELOPPEUR
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','VIEW_TEAM',
    'SUBMIT_WORKLOAD','VIEW_WORKLOAD',
    'VIEW_KPI'
) WHERE r.name = 'DEVELOPPEUR';

-- L'utilisateur admin est créé au démarrage par DataInitializer (mot de passe encodé par BCrypt)

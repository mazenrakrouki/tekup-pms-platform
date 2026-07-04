-- =============================================================
-- V13 : Capacité d'accès portefeuille (scope ADR-021)
-- -------------------------------------------------------------
-- Introduit VIEW_ALL_PROJECTS : capacité qui lève le filtre de périmètre
-- (accès portefeuille complet). Attribuée au Directeur par défaut.
-- Le Chef de projet et le Développeur n'ont PAS cette capacité : leur
-- périmètre est dérivé des relations (chef de projet / affectation) par
-- ProjectScopeService. Conforme ADR-001 : pilotage par capacité, pas par rôle.
-- =============================================================

INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
    ('VIEW_ALL_PROJECTS', 'PROJET', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- DIRECTEUR : accès portefeuille complet
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'VIEW_ALL_PROJECTS'
WHERE r.name = 'DIRECTEUR'
ON CONFLICT DO NOTHING;

-- Invalider les sessions Directeur pour recharger le nouveau set de permissions (ADR-017)
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name = 'DIRECTEUR');

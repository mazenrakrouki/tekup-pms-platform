-- =============================================================
-- V23 : Devis Interne — structure vide (spec F-AFF-13 §3)
-- -------------------------------------------------------------
-- DÉCISION 2026-07-05 (BUSINESS_ANALYSIS.md §16) : la STRUCTURE du DI
-- est implémentée comme modèle vide ; les valeurs réelles de la société
-- (salaires, marges) ne sont JAMAIS seedées — chaque déploiement saisit
-- ses propres valeurs. Les montants TND et marges sont calculés à la
-- lecture (devise × taux), jamais stockés.
-- Accès restreint à la nouvelle capacité MANAGE_DI (Directeur).
-- =============================================================

CREATE TABLE lignes_di (
    id                  BIGSERIAL     PRIMARY KEY,
    project_id          BIGINT        NOT NULL,
    section             VARCHAR(20)   NOT NULL,  -- HONORAIRES | FRAIS | AUTRES_FRAIS
    ordre               INT           NOT NULL DEFAULT 0,
    profil_contractuel  VARCHAR(120),            -- ex. "PC-1 Chef de mission"
    ressource_proposee  VARCHAR(120),            -- nom proposé dans l'offre
    ressource_retenue   VARCHAR(120),            -- staffing réel
    unite               VARCHAR(20)   NOT NULL DEFAULT 'H-Jour',
    charge_vendue_jh    NUMERIC(10,2),           -- JH vendus au contrat
    prix_vente_unitaire NUMERIC(15,2),           -- devise projet / jour
    quantite_interne_jh NUMERIC(10,2),           -- JH internes estimés
    cout_unitaire_tcc   NUMERIC(10,2),           -- TND / jour
    frais_divers        NUMERIC(15,2),           -- FD
    frais_generaux      NUMERIC(15,2),           -- FG-P&ST
    cout_impots         NUMERIC(15,2),           -- impôts directs (RS/IS, IRPP/CNSS, ENR, REDEV)
    taux_pourcentage    NUMERIC(7,4),            -- lignes taxes : % du total vendu TND (ex. 0.05)
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_ligne_di_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT chk_ligne_di_section CHECK (section IN ('HONORAIRES', 'FRAIS', 'AUTRES_FRAIS'))
);

CREATE INDEX idx_ligne_di_project ON lignes_di(project_id) WHERE deleted = FALSE;

-- Capacité MANAGE_DI : consultation + édition du Devis Interne (données sensibles).
-- Attribuée au Directeur uniquement ; ré-attribuable via le RBAC dynamique.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
    ('MANAGE_DI', 'PROJET', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'MANAGE_DI'
WHERE r.name = 'DIRECTEUR'
ON CONFLICT DO NOTHING;

-- Invalider les sessions Directeur pour recharger le nouveau set de permissions (ADR-017)
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name = 'DIRECTEUR');

-- =============================================================
-- V9 : Facturation — jalons, paiements, avenants + permissions
-- =============================================================

-- Jalons de facturation
CREATE TABLE jalons_facturation (
    id              BIGSERIAL       PRIMARY KEY,
    project_id      BIGINT          NOT NULL,
    label           VARCHAR(255)    NOT NULL,
    pourcentage     NUMERIC(5,2)    NOT NULL,   -- % du budget ; Σ active ≤ 100
    montant         NUMERIC(15,2),              -- effectiveBudget × pourcentage / 100
    date_prevue     DATE,
    date_facture    DATE,
    statut          VARCHAR(20)     NOT NULL DEFAULT 'PREVU',
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_jf_project      FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT chk_jf_pourcentage CHECK (pourcentage > 0 AND pourcentage <= 100),
    CONSTRAINT chk_jf_statut      CHECK (statut IN ('PREVU','FACTURE','PAYE'))
);

CREATE INDEX idx_jf_project ON jalons_facturation(project_id) WHERE deleted = FALSE;

-- Paiements liés aux jalons
CREATE TABLE paiements (
    id              BIGSERIAL       PRIMARY KEY,
    jalon_id        BIGINT          NOT NULL,
    montant_recu    NUMERIC(15,2)   NOT NULL,
    date_paiement   DATE            NOT NULL,
    reference       VARCHAR(255),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_pmt_jalon   FOREIGN KEY (jalon_id) REFERENCES jalons_facturation(id),
    CONSTRAINT chk_pmt_montant CHECK (montant_recu > 0)
);

CREATE INDEX idx_pmt_jalon ON paiements(jalon_id) WHERE deleted = FALSE;

-- Avenants (amendments contractuels → modifient revised_budget)
CREATE TABLE avenants (
    id              BIGSERIAL       PRIMARY KEY,
    project_id      BIGINT          NOT NULL,
    numero          VARCHAR(50)     NOT NULL,
    objet           VARCHAR(500),
    montant         NUMERIC(15,2)   NOT NULL,   -- positif = augmentation, négatif = réduction
    date_avenant    DATE            NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_av_project FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE INDEX idx_av_project ON avenants(project_id) WHERE deleted = FALSE;

-- ── Permissions facturation ──────────────────────────────────────
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_BILLING', 'BILLING', NOW(), NOW(), FALSE),
  ('VIEW_BILLING',   'BILLING', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET') AND p.code = 'MANAGE_BILLING'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET', 'DIRECTEUR') AND p.code = 'VIEW_BILLING'
ON CONFLICT DO NOTHING;

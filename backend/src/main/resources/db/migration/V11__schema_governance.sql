-- =============================================================
-- V11 : Gouvernance — Risques, Livrables, Parties Prenantes,
--        Demandes de Changement
-- =============================================================

-- ── Risques ──────────────────────────────────────────────────
CREATE TABLE risks (
    id               BIGSERIAL       PRIMARY KEY,
    project_id       BIGINT          NOT NULL,
    description      VARCHAR(1000)   NOT NULL,
    probabilite      VARCHAR(10)     NOT NULL DEFAULT 'MOYEN',
    impact           VARCHAR(10)     NOT NULL DEFAULT 'MOYEN',
    plan_mitigation  VARCHAR(1000),
    statut           VARCHAR(10)     NOT NULL DEFAULT 'OUVERT',
    created_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted          BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_risk_project     FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT chk_risk_probabilite CHECK (probabilite IN ('FAIBLE','MOYEN','ELEVE')),
    CONSTRAINT chk_risk_impact      CHECK (impact      IN ('FAIBLE','MOYEN','ELEVE')),
    CONSTRAINT chk_risk_statut      CHECK (statut      IN ('OUVERT','MITIGE','FERME'))
);

CREATE INDEX idx_risk_project ON risks(project_id) WHERE deleted = FALSE;

-- ── Livrables ─────────────────────────────────────────────────
CREATE TABLE livrables (
    id             BIGSERIAL      PRIMARY KEY,
    project_id     BIGINT         NOT NULL,
    titre          VARCHAR(255)   NOT NULL,
    description    VARCHAR(1000),
    date_echeance  DATE,
    statut         VARCHAR(15)    NOT NULL DEFAULT 'EN_ATTENTE',
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted        BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_livrable_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT chk_livrable_statut CHECK (statut IN ('EN_ATTENTE','EN_COURS','LIVRE','VALIDE'))
);

CREATE INDEX idx_livrable_project ON livrables(project_id) WHERE deleted = FALSE;

-- ── Parties Prenantes ─────────────────────────────────────────
CREATE TABLE parties_prenantes (
    id          BIGSERIAL     PRIMARY KEY,
    project_id  BIGINT        NOT NULL,
    nom         VARCHAR(255)  NOT NULL,
    fonction    VARCHAR(255),
    email       VARCHAR(255),
    telephone   VARCHAR(50),
    influence   VARCHAR(10)   NOT NULL DEFAULT 'MOYEN',
    interet     VARCHAR(10)   NOT NULL DEFAULT 'MOYEN',
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_pp_project    FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT chk_pp_influence CHECK (influence IN ('FAIBLE','MOYEN','ELEVE')),
    CONSTRAINT chk_pp_interet   CHECK (interet   IN ('FAIBLE','MOYEN','ELEVE'))
);

CREATE INDEX idx_pp_project ON parties_prenantes(project_id) WHERE deleted = FALSE;

-- ── Demandes de Changement ────────────────────────────────────
CREATE TABLE demandes_changement (
    id             BIGSERIAL     PRIMARY KEY,
    project_id     BIGINT        NOT NULL,
    demandeur_id   BIGINT        NOT NULL,
    titre          VARCHAR(255)  NOT NULL,
    description    VARCHAR(1000),
    priorite       VARCHAR(10)   NOT NULL DEFAULT 'NORMALE',
    statut         VARCHAR(15)   NOT NULL DEFAULT 'EN_ATTENTE',
    date_demande   DATE          NOT NULL,
    date_decision  DATE,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted        BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_dc_project  FOREIGN KEY (project_id)   REFERENCES projects(id),
    CONSTRAINT fk_dc_user     FOREIGN KEY (demandeur_id) REFERENCES users(id),
    CONSTRAINT chk_dc_priorite CHECK (priorite IN ('FAIBLE','NORMALE','ELEVEE','CRITIQUE')),
    CONSTRAINT chk_dc_statut   CHECK (statut   IN ('EN_ATTENTE','APPROUVE','REJETE'))
);

CREATE INDEX idx_dc_project ON demandes_changement(project_id) WHERE deleted = FALSE;

-- ── Permissions gouvernance ───────────────────────────────────
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_GOVERNANCE', 'GOVERNANCE', NOW(), NOW(), FALSE),
  ('VIEW_GOVERNANCE',   'GOVERNANCE', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET') AND p.code = 'MANAGE_GOVERNANCE'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET', 'DIRECTEUR', 'DEVELOPPEUR') AND p.code = 'VIEW_GOVERNANCE'
ON CONFLICT DO NOTHING;

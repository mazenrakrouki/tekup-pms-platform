-- =============================================================
-- V10 : Missions professionnelles + composantes de coût
-- =============================================================

CREATE TABLE missions (
    id              BIGSERIAL       PRIMARY KEY,
    project_id      BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    objet           VARCHAR(500)    NOT NULL,
    lieu            VARCHAR(255),
    date_debut      DATE            NOT NULL,
    date_fin        DATE            NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_mission_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_mission_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    CONSTRAINT chk_mission_dates  CHECK (date_fin >= date_debut)
);

CREATE INDEX idx_mission_project ON missions(project_id) WHERE deleted = FALSE;
CREATE INDEX idx_mission_user    ON missions(user_id)    WHERE deleted = FALSE;

-- Composantes de coût d'une mission
CREATE TABLE composantes_mission (
    id                BIGSERIAL       PRIMARY KEY,
    mission_id        BIGINT          NOT NULL,
    type_composante   VARCHAR(30)     NOT NULL,
    montant           NUMERIC(15,2)   NOT NULL,
    devise            VARCHAR(3)      NOT NULL DEFAULT 'TND',
    description       VARCHAR(500),
    created_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted           BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_comp_mission   FOREIGN KEY (mission_id) REFERENCES missions(id),
    CONSTRAINT chk_comp_type     CHECK (type_composante IN ('PERDIEM','BILLET','TIMBRE','TRANSPORT','SEJOUR')),
    CONSTRAINT chk_comp_montant  CHECK (montant > 0)
);

CREATE INDEX idx_comp_mission ON composantes_mission(mission_id) WHERE deleted = FALSE;

-- ── Permissions missions ─────────────────────────────────────────
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_MISSION', 'MISSION', NOW(), NOW(), FALSE),
  ('VIEW_MISSION',   'MISSION', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET') AND p.code = 'MANAGE_MISSION'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET', 'DIRECTEUR', 'DEVELOPPEUR') AND p.code = 'VIEW_MISSION'
ON CONFLICT DO NOTHING;

-- V11: governance module - risks, deliverables, stakeholders, change requests - plus its two permissions.
-- Governance is the non-financial side of running a project: what a PM tracks besides money and days.
-- Ships as one migration because the four tables share the same two permissions and belong to one feature.

-- ── Risks ──────────────────────────────────────────────────
CREATE TABLE risks (
    id               BIGSERIAL       PRIMARY KEY,
    -- Every governance row hangs under one project; the module is reached via /api/projects/{projectId}/...
    project_id       BIGINT          NOT NULL,
    -- What could go wrong; 1000 chars since a risk is a full sentence, not a title.
    description      VARCHAR(1000)   NOT NULL,
    -- Likelihood and impact, mapped by the enum NiveauRisque (FAIBLE/MOYEN/ELEVE). Defaults to MOYEN so a
    -- hastily written risk lands in the middle of the matrix instead of empty.
    probabilite      VARCHAR(10)     NOT NULL DEFAULT 'MOYEN',
    impact           VARCHAR(10)     NOT NULL DEFAULT 'MOYEN',
    -- Planned response; nullable since a risk is often logged before anyone knows how to answer it.
    plan_mitigation  VARCHAR(1000),
    -- OUVERT (open) / MITIGE (mitigated) / FERME (closed), mapped by the enum StatutRisque.
    statut           VARCHAR(10)     NOT NULL DEFAULT 'OUVERT',
    -- The three columns every PMS table carries (see V8 for the detail).
    created_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted          BOOLEAN         NOT NULL DEFAULT FALSE,
    -- FK: a risk with no project could never be shown anywhere.
    CONSTRAINT fk_risk_project     FOREIGN KEY (project_id) REFERENCES projects(id),
    -- Pins the allowed words so Hibernate's enum-by-name mapping never throws "No enum constant" on open.
    CONSTRAINT chk_risk_probabilite CHECK (probabilite IN ('FAIBLE','MOYEN','ELEVE')),
    CONSTRAINT chk_risk_impact      CHECK (impact      IN ('FAIBLE','MOYEN','ELEVE')),
    CONSTRAINT chk_risk_statut      CHECK (statut      IN ('OUVERT','MITIGE','FERME'))
);

-- Live risks of one project. Partial index (deleted = FALSE) like the rest of this schema.
CREATE INDEX idx_risk_project ON risks(project_id) WHERE deleted = FALSE;

-- ── Deliverables ─────────────────────────────────────────────
CREATE TABLE livrables (
    id             BIGSERIAL      PRIMARY KEY,
    project_id     BIGINT         NOT NULL,
    titre          VARCHAR(255)   NOT NULL,
    description    VARCHAR(1000),
    -- Nullable: the deliverable list may be fixed long before its dates are.
    date_echeance  DATE,
    -- State machine (enum StatutLivrable): EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE (final, then locked).
    -- A separate "accepted" state matters because client acceptance is a contractual event a boolean can't capture.
    statut         VARCHAR(15)    NOT NULL DEFAULT 'EN_ATTENTE',
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted        BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_livrable_project FOREIGN KEY (project_id) REFERENCES projects(id),
    -- Same "No enum constant" protection as the risks above.
    CONSTRAINT chk_livrable_statut CHECK (statut IN ('EN_ATTENTE','EN_COURS','LIVRE','VALIDE'))
);

-- Live deliverables of one project. Also read by KpiService for delivery_pct (V22): rows LIVRE or VALIDE over
-- the total - it's the share already handed over, not only the share accepted, so it doesn't fall on acceptance.
CREATE INDEX idx_livrable_project ON livrables(project_id) WHERE deleted = FALSE;

-- ── Stakeholders ─────────────────────────────────────────────
CREATE TABLE parties_prenantes (
    id          BIGSERIAL     PRIMARY KEY,
    project_id  BIGINT        NOT NULL,
    nom         VARCHAR(255)  NOT NULL,
    -- Job title on the client side ("Directeur des systemes d'information").
    fonction    VARCHAR(255),
    -- Contact details, both nullable and free text: a stakeholder is often logged from a meeting with only a
    -- name and role, email added later.
    email       VARCHAR(255),
    telephone   VARCHAR(50),
    -- Power over the project vs. how much the project matters to them - the classic stakeholder matrix.
    -- Reuses the risks' NiveauRisque enum since the three levels are identical, for one shared set of labels.
    influence   VARCHAR(10)   NOT NULL DEFAULT 'MOYEN',
    interet     VARCHAR(10)   NOT NULL DEFAULT 'MOYEN',
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_pp_project    FOREIGN KEY (project_id) REFERENCES projects(id),
    -- Same "No enum constant" protection as above, on the two level columns.
    CONSTRAINT chk_pp_influence CHECK (influence IN ('FAIBLE','MOYEN','ELEVE')),
    CONSTRAINT chk_pp_interet   CHECK (interet   IN ('FAIBLE','MOYEN','ELEVE'))
);

-- Live stakeholders of one project.
CREATE INDEX idx_pp_project ON parties_prenantes(project_id) WHERE deleted = FALSE;

-- ── Change requests ───────────────────────────────────────────
CREATE TABLE demandes_changement (
    id             BIGSERIAL     PRIMARY KEY,
    project_id     BIGINT        NOT NULL,
    -- Requester, as a real link to users (not typed-in text) so the request stays traceable to a person even
    -- after they leave, and the displayed name follows any correction to the user row.
    demandeur_id   BIGINT        NOT NULL,
    titre          VARCHAR(255)  NOT NULL,
    description    VARCHAR(1000),
    -- FAIBLE/NORMALE/ELEVEE/CRITIQUE, mapped by enum PrioriteChangement. Feminine endings (ELEVEE): a separate
    -- enum from NiveauRisque, the two lists must not be mixed up.
    priorite       VARCHAR(10)   NOT NULL DEFAULT 'NORMALE',
    -- EN_ATTENTE (pending) -> APPROUVE or REJETE, mapped by enum StatutChangement.
    statut         VARCHAR(15)   NOT NULL DEFAULT 'EN_ATTENTE',
    -- Day the change was requested; required so the request can be placed in the project's history.
    date_demande   DATE          NOT NULL,
    -- Nullable on purpose: empty means "still pending a decision"; forcing a value would fake a date.
    date_decision  DATE,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted        BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_dc_project  FOREIGN KEY (project_id)   REFERENCES projects(id),
    -- Requester must be a real account, so a removed user can't leave requests attributed to nobody.
    CONSTRAINT fk_dc_user     FOREIGN KEY (demandeur_id) REFERENCES users(id),
    -- Same "No enum constant" protection as everywhere else in this file.
    CONSTRAINT chk_dc_priorite CHECK (priorite IN ('FAIBLE','NORMALE','ELEVEE','CRITIQUE')),
    CONSTRAINT chk_dc_statut   CHECK (statut   IN ('EN_ATTENTE','APPROUVE','REJETE'))
);

-- Live change requests of one project. No index on demandeur_id: no screen queries by requester.
CREATE INDEX idx_dc_project ON demandes_changement(project_id) WHERE deleted = FALSE;

-- ── Governance permissions ────────────────────────────────────
-- Data-driven permissions (ADR-001), same pattern as V9/V10: rows here, @PreAuthorize(...) in the services.
-- One pair of permissions covers all four tables on purpose - they're handled by the same person in one meeting.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_GOVERNANCE', 'GOVERNANCE', NOW(), NOW(), FALSE),
  ('VIEW_GOVERNANCE',   'GOVERNANCE', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- Grants the write permission to the two roles that run projects. V12 later rebuilds this whole matrix.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET') AND p.code = 'MANAGE_GOVERNANCE'
ON CONFLICT DO NOTHING;

-- Grants the read permission to four roles, developer included: a risk or deliverable is not money (BR-050),
-- and ProjectScopeInterceptor still limits him to his own projects regardless of holding the permission.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET', 'DIRECTEUR', 'DEVELOPPEUR') AND p.code = 'VIEW_GOVERNANCE'
ON CONFLICT DO NOTHING;

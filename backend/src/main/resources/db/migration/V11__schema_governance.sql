-- =============================================================
-- V11 : Governance - Risks, Deliverables, Stakeholders,
--        Change Requests
--        (original French title: "Gouvernance - Risques, Livrables,
--         Parties Prenantes, Demandes de Changement")
-- =============================================================
-- WHAT THIS FILE IS
--   One Flyway migration. It creates the four tables of the governance module
--   and registers its two permissions. Governance is the non-financial side of
--   running a project - what a project manager tracks besides money and days:
--     risks               = what could go wrong, how likely, how bad, what we
--                           plan to do about it;
--     livrables           = the deliverables owed to the client and where each
--                           one stands;
--     parties_prenantes   = the people around the project (stakeholders) and
--                           how much they matter;
--     demandes_changement = the change requests raised on the project and the
--                           decision taken on each.
--
-- WHERE IT SITS IN THE FLOW
--   Before it: V5 created "projects" and V1 created "users". After it: V12
--   rebuilds the whole role/permission matrix.
--   At runtime, one controller and one service per table:
--   RiskController / LivrableController / PartiePrenanteController /
--   DemandeChangementController, all under /api/projects/{projectId}/...
--   -> ProjectScopeInterceptor (ADR-021) -> the matching service, which carries
--   the @PreAuthorize check -> the matching repository.
--   The Java entities are com.pms.governance.entity.Risk, Livrable,
--   PartiePrenante and DemandeChangement.
--
-- WHY IT EXISTS
--   These four tables are what turns PMS from a cost-tracking tool into a
--   project-management tool. Without them, the monthly review could show how
--   much a project has spent but not why it is late, what is still owed to the
--   client, who has to be convinced, or what the client asked to change.
--
-- WHY FOUR TABLES IN ONE MIGRATION
--   They ship together because they are one feature and they share the same two
--   permissions. A Flyway migration is applied as one unit, so a database can
--   never end up with the risks table but no deliverables table.
-- =============================================================

-- ── Risks ──────────────────────────────────────────────────
CREATE TABLE risks (
    id               BIGSERIAL       PRIMARY KEY,
    -- Every governance row hangs under exactly one project. NOT NULL, because
    -- the whole module is reached through /api/projects/{projectId}/...
    project_id       BIGINT          NOT NULL,
    -- What could go wrong, in words. 1000 characters: a risk is described in
    -- full sentences, not in a title.
    description      VARCHAR(1000)   NOT NULL,
    -- The two axes of the classic risk matrix: how likely, and how bad.
    -- Both are text mapped by the Java enum NiveauRisque (FAIBLE / MOYEN /
    -- ELEVE = low / medium / high), with @Enumerated(EnumType.STRING).
    -- DEFAULT 'MOYEN' so that a risk written down in a hurry still lands in the
    -- middle of the matrix instead of arriving empty.
    probabilite      VARCHAR(10)     NOT NULL DEFAULT 'MOYEN',
    impact           VARCHAR(10)     NOT NULL DEFAULT 'MOYEN',
    -- What we plan to do if it happens. Nullable: a risk is often recorded
    -- before anyone knows how to answer it.
    plan_mitigation  VARCHAR(1000),
    -- Where the risk stands: OUVERT (open), MITIGE (mitigated), FERME (closed).
    -- Mapped by the enum StatutRisque.
    statut           VARCHAR(10)     NOT NULL DEFAULT 'OUVERT',
    -- The three columns every PMS table carries (see V8 for the detail).
    created_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted          BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Foreign key: a risk that belongs to no project could never be shown
    -- anywhere, and the risk list would crash while reading project.code.
    CONSTRAINT fk_risk_project     FOREIGN KEY (project_id) REFERENCES projects(id),
    -- WHAT: these three CHECK constraints pin the allowed words.
    -- WHY : Hibernate maps the text back to the enum by NAME. A row holding
    --       'ELEVEE' or 'eleve' would make it throw "No enum constant
    --       com.pms.governance.entity.NiveauRisque.ELEVEE" as soon as somebody
    --       opens the risk list - a blank screen with no visible explanation.
    --       The Java enum already limits what the API can write; this is the
    --       guard for the demo seed of V26 and for any manual SQL.
    -- WITHOUT THEM: the risk matrix on screen would silently drop the rows whose
    --       level it cannot read, and a high risk could disappear from view.
    CONSTRAINT chk_risk_probabilite CHECK (probabilite IN ('FAIBLE','MOYEN','ELEVE')),
    CONSTRAINT chk_risk_impact      CHECK (impact      IN ('FAIBLE','MOYEN','ELEVE')),
    CONSTRAINT chk_risk_statut      CHECK (statut      IN ('OUVERT','MITIGE','FERME'))
);

-- WHAT: index for "all the live risks of project 42".
-- WHY : the same partial-index pattern used all over this schema - only the
--       rows with deleted = FALSE are indexed, because every query filters on
--       them, so the index stays small and soft-deleted rows cost nothing.
-- WITHOUT IT: opening the risk tab of one project would read every risk row of
--       every project of the company.
CREATE INDEX idx_risk_project ON risks(project_id) WHERE deleted = FALSE;

-- ── Deliverables ─────────────────────────────────────────────
CREATE TABLE livrables (
    id             BIGSERIAL      PRIMARY KEY,
    project_id     BIGINT         NOT NULL,
    titre          VARCHAR(255)   NOT NULL,
    description    VARCHAR(1000),
    -- When the deliverable is due. Nullable: the contract may fix the list of
    -- deliverables long before it fixes their dates.
    date_echeance  DATE,
    -- The state machine of a deliverable, held by the Java enum StatutLivrable:
    --   EN_ATTENTE (waiting) -> EN_COURS (in progress) -> LIVRE (delivered)
    --   -> VALIDE (accepted by the client).
    -- LivrableService also allows the jump EN_ATTENTE -> LIVRE for a small
    -- deliverable that is written and sent in one go, and VALIDE is final:
    -- nothing can move a deliverable out of it, and update and delete are both
    -- refused once it is reached.
    -- WHY a state machine and not a free "done" flag: acceptance by the client
    -- is a contractual event, so "delivered" and "accepted" must be two
    -- different states. A single boolean could not tell them apart.
    statut         VARCHAR(15)    NOT NULL DEFAULT 'EN_ATTENTE',
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted        BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_livrable_project FOREIGN KEY (project_id) REFERENCES projects(id),
    -- Same reason as for the risks above: the four words must match the enum
    -- names exactly, or Hibernate throws "No enum constant" when the tab opens.
    CONSTRAINT chk_livrable_statut CHECK (statut IN ('EN_ATTENTE','EN_COURS','LIVRE','VALIDE'))
);

-- Index for "all the live deliverables of project 42", the deliverables tab.
-- KpiService also reads this list, to produce delivery_pct (V22): it counts the
-- rows whose statut is LIVRE **or** VALIDE and divides by the total number of
-- deliverables. Careful with the wording in front of the jury - it is the share
-- already HANDED OVER, not only the share accepted by the client. Counting
-- VALIDE alone would make the percentage FALL the day the client accepts a
-- deliverable, because the row would leave LIVRE.
-- It is the only progress figure PMS can compute on its own; the other one
-- (ev_pct) is typed in by the project manager.
CREATE INDEX idx_livrable_project ON livrables(project_id) WHERE deleted = FALSE;

-- ── Stakeholders ─────────────────────────────────────────────
CREATE TABLE parties_prenantes (
    id          BIGSERIAL     PRIMARY KEY,
    project_id  BIGINT        NOT NULL,
    nom         VARCHAR(255)  NOT NULL,
    -- Job title on the client side ("Directeur des systemes d'information").
    fonction    VARCHAR(255),
    -- Contact details. Both nullable, and deliberately plain text with no
    -- format constraint: a stakeholder is often recorded from a meeting with
    -- only a name and a role, and an e-mail added later.
    email       VARCHAR(255),
    telephone   VARCHAR(50),
    -- The two axes of the stakeholder matrix: how much power the person has over
    -- the project (influence) and how much the project matters to them
    -- (interet). Crossing the two says who must be convinced, who must simply be
    -- kept informed, and who can be left alone.
    -- Both reuse the SAME Java enum as the risks, NiveauRisque (FAIBLE / MOYEN /
    -- ELEVE). Why reuse it in a file that has nothing to do with risk: the three
    -- levels are identical, and one shared enum means the frontend can colour
    -- both matrices with one single set of labels and translations.
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

-- Index for "all the live stakeholders of project 42".
CREATE INDEX idx_pp_project ON parties_prenantes(project_id) WHERE deleted = FALSE;

-- ── Change requests ───────────────────────────────────────────
CREATE TABLE demandes_changement (
    id             BIGSERIAL     PRIMARY KEY,
    project_id     BIGINT        NOT NULL,
    -- WHO asked for the change, as a link to users.
    -- WHY a real link and not a typed-in name: a change request is an
    -- accountable act. Keeping the user id means the request can still be traced
    -- back to a person after they leave the team, and the name shown on screen
    -- follows the user row if it is corrected.
    demandeur_id   BIGINT        NOT NULL,
    titre          VARCHAR(255)  NOT NULL,
    description    VARCHAR(1000),
    -- How urgent the request is: FAIBLE / NORMALE / ELEVEE / CRITIQUE, mapped by
    -- the Java enum PrioriteChangement. Note the feminine French endings
    -- (ELEVEE, not ELEVE): this is a separate enum from NiveauRisque, so the two
    -- lists must not be mixed up.
    priorite       VARCHAR(10)   NOT NULL DEFAULT 'NORMALE',
    -- The decision: EN_ATTENTE (pending) -> APPROUVE or REJETE, mapped by the
    -- enum StatutChangement. A request starts pending, which is why the default
    -- is set here and not left to the application.
    statut         VARCHAR(15)   NOT NULL DEFAULT 'EN_ATTENTE',
    -- The day the change was asked for: NOT NULL, because a request with no date
    -- cannot be placed in the history of the project.
    date_demande   DATE          NOT NULL,
    -- The day it was decided: nullable ON PURPOSE. It stays empty while the
    -- request is pending, and that emptiness is exactly what "still waiting for a
    -- decision" means. Making it NOT NULL would force a fake date on every open
    -- request.
    date_decision  DATE,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted        BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_dc_project  FOREIGN KEY (project_id)   REFERENCES projects(id),
    -- Second foreign key, this one to users: the requester must be a real
    -- account. Without it, a user row removed in raw SQL would leave change
    -- requests asked for by nobody, and the list would crash while reading the
    -- requester's name.
    CONSTRAINT fk_dc_user     FOREIGN KEY (demandeur_id) REFERENCES users(id),
    -- Same "No enum constant" protection as everywhere else in this file.
    CONSTRAINT chk_dc_priorite CHECK (priorite IN ('FAIBLE','NORMALE','ELEVEE','CRITIQUE')),
    CONSTRAINT chk_dc_statut   CHECK (statut   IN ('EN_ATTENTE','APPROUVE','REJETE'))
);

-- Index for "all the live change requests of project 42".
-- Note there is deliberately no index on demandeur_id: no screen asks "every
-- change request raised by person X", so an extra index would only cost time on
-- every insert and update for nothing.
CREATE INDEX idx_dc_project ON demandes_changement(project_id) WHERE deleted = FALSE;

-- ── Governance permissions ────────────────────────────────────
-- Same pattern as V9 and V10: authorization is dynamic and permission-based
-- (ADR-001), so a permission is a ROW created here as data, and the services
-- carry @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')"). No code anywhere
-- tests a role name.
-- ONE PAIR OF PERMISSIONS FOR FOUR TABLES, and that is a decision, not an
-- oversight: risks, deliverables, stakeholders and change requests are handled
-- by the same person in the same meeting. Four separate pairs would have added
-- six more rows to the matrix without ever being granted differently.
-- ON CONFLICT (code) DO NOTHING guards a replay against uk_permissions_code (V1).
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_GOVERNANCE', 'GOVERNANCE', NOW(), NOW(), FALSE),
  ('VIEW_GOVERNANCE',   'GOVERNANCE', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- WHAT: gives the write permission to the two roles that run projects.
-- HOW : "FROM roles r, permissions p WHERE ..." is a cross join narrowed by the
--       WHERE clause, so each matching role is paired with the matching
--       permission. The ids come from sequences and cannot be hard-coded.
-- NOTE: V12 later deletes every grant of these four roles and rebuilds the
--       matrix, so this block only describes the state between V11 and V12.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET') AND p.code = 'MANAGE_GOVERNANCE'
ON CONFLICT DO NOTHING;

-- WHAT: gives the read permission to four roles, the developer included.
-- WHY the developer may read it although he is walled off from financial data
--       (BR-050): a risk or a deliverable is not money. A developer needs to
--       know what is owed to the client and what is already flagged as risky.
--       He still only sees the projects inside his own perimeter, because the
--       URL goes through ProjectScopeInterceptor (ADR-021): holding the
--       permission is never enough on its own.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET', 'DIRECTEUR', 'DEVELOPPEUR') AND p.code = 'VIEW_GOVERNANCE'
ON CONFLICT DO NOTHING;

-- =============================================================
-- V27 : Planification agile — Sprints et Backlog produit
--
-- Répond à l'exigence « planification agile (sprints, backlog) »
-- du sujet. Module autonome : il ne référence que projects(id).
-- Aucune table financière (facturation, charges, devis interne,
-- snapshots KPI) n'est lue ni modifiée.
-- =============================================================

-- ── Sprints ──────────────────────────────────────────────────
CREATE TABLE sprints (
    id          BIGSERIAL      PRIMARY KEY,
    project_id  BIGINT         NOT NULL,
    name        VARCHAR(100)   NOT NULL,
    goal        VARCHAR(1000),
    start_date  DATE,
    end_date    DATE,
    status      VARCHAR(10)    NOT NULL DEFAULT 'PLANNED',
    created_at  TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP      NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted     BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_sprint_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT chk_sprint_status CHECK (status IN ('PLANNED','ACTIVE','CLOSED')),
    -- Une itération qui se termine avant d'avoir commencé est une saisie erronée.
    -- Contrôlé aussi côté service, mais la base reste la dernière ligne de défense.
    CONSTRAINT chk_sprint_dates  CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_sprint_project ON sprints(project_id) WHERE deleted = FALSE;

-- ── Backlog produit ──────────────────────────────────────────
-- sprint_id est nullable : un élément sans sprint appartient au backlog produit,
-- pas encore engagé dans une itération. ON DELETE SET NULL complète la suppression
-- logique côté service — si un sprint venait à être supprimé physiquement, ses
-- éléments retourneraient au backlog au lieu de violer la clé étrangère.
CREATE TABLE backlog_items (
    id             BIGSERIAL      PRIMARY KEY,
    project_id     BIGINT         NOT NULL,
    sprint_id      BIGINT,
    title          VARCHAR(255)   NOT NULL,
    description    VARCHAR(2000),
    priority       VARCHAR(10)    NOT NULL DEFAULT 'MEDIUM',
    -- Estimation en jours-homme (JH), unité de toute la plateforme : plan de charge,
    -- TCC, indicateurs. Pas de points de story, qui seraient une seconde unité sans
    -- conversion possible vers le modèle de coût.
    estimate_days  NUMERIC(6,2),
    status         VARCHAR(15)    NOT NULL DEFAULT 'TODO',
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(255),
    updated_by     VARCHAR(255),
    deleted        BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_backlog_project  FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_backlog_sprint   FOREIGN KEY (sprint_id)  REFERENCES sprints(id) ON DELETE SET NULL,
    CONSTRAINT chk_backlog_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT chk_backlog_status   CHECK (status   IN ('TODO','IN_PROGRESS','DONE')),
    CONSTRAINT chk_backlog_estimate CHECK (estimate_days IS NULL OR estimate_days >= 0)
);

CREATE INDEX idx_backlog_project ON backlog_items(project_id) WHERE deleted = FALSE;
CREATE INDEX idx_backlog_sprint  ON backlog_items(sprint_id)  WHERE deleted = FALSE AND sprint_id IS NOT NULL;

-- ── Permissions agiles ───────────────────────────────────────
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_AGILE', 'AGILE', NOW(), NOW(), FALSE),
  ('VIEW_AGILE',   'AGILE', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- ATTENTION : ne pas reprendre le schéma d'attribution de V11 ('ADMIN','CHEF_PROJET').
-- V12 a vidé role_permissions puis reconstruit la matrice cible, ramenant ADMIN à
-- l'administration de la plateforme et au TCC uniquement. Accorder l'agile à ADMIN
-- réintroduirait précisément ce que cet audit avait corrigé.

-- Gestion : le chef de projet conduit l'itération (aligné sur MANAGE_GOVERNANCE / MANAGE_MISSION).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'CHEF_PROJET' AND p.code = 'MANAGE_AGILE'
ON CONFLICT DO NOTHING;

-- Lecture : directeur (vue portefeuille), chef de projet, et développeur — un tableau
-- que l'équipe ne peut pas consulter n'a aucun intérêt. Aucune donnée financière n'y
-- figure, BR-050 n'est donc pas concernée.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('DIRECTEUR','CHEF_PROJET','DEVELOPPEUR') AND p.code = 'VIEW_AGILE'
ON CONFLICT DO NOTHING;

-- Invalidation immédiate des sessions en cours pour que les nouvelles permissions
-- soient prises en compte sans reconnexion manuelle (ADR-017).
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));

-- =============================================================
-- V8 : Snapshots KPI projets
-- =============================================================

CREATE TABLE snapshot_kpis (
    id                BIGSERIAL     PRIMARY KEY,
    project_id        BIGINT        NOT NULL,
    snapshot_date     DATE          NOT NULL,
    budget_planifie   NUMERIC(15,2),
    budget_consome    NUMERIC(15,2),
    eac               NUMERIC(15,2),
    marge             NUMERIC(15,2),
    taux_consommation NUMERIC(7,4),       -- ex. 0.7532 = 75.32 %
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted           BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_kpi_project FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE UNIQUE INDEX uk_kpi_project_date ON snapshot_kpis(project_id, snapshot_date) WHERE deleted = FALSE;
CREATE INDEX        idx_kpi_project     ON snapshot_kpis(project_id)               WHERE deleted = FALSE;

-- V8: creates snapshot_kpis. One row is a frozen photo of the money/progress
-- figures of ONE project on ONE day (V22 later adds the EVM columns to this
-- same table).
--
-- This is the one deliberate exception to PMS's rule that computed amounts
-- are derived on read, never stored: the live KPI screen (KpiService.computeLive)
-- always recomputes from source tables and never reads these columns. What's
-- stored here is HISTORY — the figures as discussed at one monthly review.
-- Exception worth knowing: computeLive does read three columns via
-- latestSnapshotEv() (ev_pct, date_fin_estimee, faits_marquants) — those are
-- V22 additions holding the PM's own judgement (typed in at review time), not
-- derived figures, so the live screen carries the last ones forward between reviews.
-- Delete this table and old reviews could never be checked again — correcting
-- a timesheet would silently rewrite what last March looked like.
--
-- application.yml sets Hibernate ddl-auto to "validate": every type/size below
-- is compared against the entity at startup and a mismatch stops the app.

CREATE TABLE snapshot_kpis (
    id                BIGSERIAL     PRIMARY KEY,
    project_id        BIGINT        NOT NULL,
    -- DATE not TIMESTAMP: a review is a working day, and this is what makes
    -- "one snapshot per project per day" (below) possible.
    snapshot_date     DATE          NOT NULL,
    -- Planned cost: every planned day × daily rate × TCC, summed by KpiService.
    budget_planifie   NUMERIC(15,2),
    -- Cost already consumed: same computation over validated timesheets.
    budget_consome    NUMERIC(15,2),
    -- EAC ("Estimate At Completion"): consumed cost + the unconsumed part of the plan.
    eac               NUMERIC(15,2),
    -- Expected margin = budget (in TND) − EAC. Nullable: NULL means "not known
    -- yet" (a 0 would misread as "no margin left" and raise a false alarm).
    marge             NUMERIC(15,2),
    -- Consumption RATIO, not a percentage: 0.7532 = 75.32% (frontend does ×100).
    -- NUMERIC(7,4): the screen shows 2 decimals, so the ratio needs 2 more to stay exact.
    taux_consommation NUMERIC(7,4),       -- e.g. 0.7532 = 75.32 %
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    -- Soft delete: a financial history that can be erased is worth nothing at audit.
    deleted           BOOLEAN       NOT NULL DEFAULT FALSE,
    -- No ON DELETE clause: PostgreSQL refuses to delete a project with
    -- snapshots, which is fine since projects are only ever soft-deleted.
    CONSTRAINT fk_kpi_project FOREIGN KEY (project_id) REFERENCES projects(id)
);

-- At most one LIVE snapshot per project per day (partial index: WHERE deleted
-- = FALSE). Without the WHERE clause, deleting today's snapshot and creating
-- a corrected one would fail on a duplicate key. Without the index at all,
-- KpiService.latestSnapshotEv() (sorts by snapshot_date) could pick either of
-- two same-day rows at random.
CREATE UNIQUE INDEX uk_kpi_project_date ON snapshot_kpis(project_id, snapshot_date) WHERE deleted = FALSE;
-- Backs "every live snapshot of project X" (SnapshotKpiRepository.findActiveByProjectId).
CREATE INDEX        idx_kpi_project     ON snapshot_kpis(project_id)               WHERE deleted = FALSE;

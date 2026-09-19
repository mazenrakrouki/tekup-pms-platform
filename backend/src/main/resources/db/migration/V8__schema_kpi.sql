-- =============================================================
-- V8 : Project KPI snapshots (original French title: "Snapshots KPI projets")
-- =============================================================
-- WHAT THIS FILE IS
--   One Flyway migration script. Flyway is the tool that owns the database
--   schema (ADR-019): it runs every V<number>__*.sql file once, in number
--   order, and writes down which ones it has already applied. This one creates
--   the table snapshot_kpis. One row there is a frozen photo of the money and
--   progress figures of ONE project on ONE day.
--
-- WHERE IT SITS IN THE FLOW
--   Before it: V5 created "projects" (the foreign key below needs that table)
--   and V7 created plan_charges / charges_reelles (the tables the figures are
--   computed from). After it: V22 ADDs the EVM columns (ev_pct, delivery_pct,
--   fae, marge_actuelle...) to this same table, so the real column list of
--   snapshot_kpis is "V8 + V22".
--   At runtime: KpiController (GET and POST
--   /api/projects/{projectId}/kpi/snapshots) -> KpiService.createSnapshot()
--   reads the live tables, computes the figures and copies them here through
--   SnapshotKpiRepository. The Java class mapped on this table is
--   com.pms.kpi.entity.SnapshotKpi.
--
-- WHY IT EXISTS
--   Everywhere else in PMS a computed amount is derived when it is read and is
--   never kept in a column - that is the firm rule of the Devis Interne
--   (DI = internal quote). This table is the one deliberate exception, and it
--   is NOT a cache: the live KPI screen (KpiService.computeLive) recomputes
--   every money figure from the source tables and never reads the columns
--   created below. What is stored here is HISTORY: the figures exactly as they
--   were discussed at one monthly project review.
--   ONE PRECISION THE JURY MAY PUSH ON: computeLive does read three columns of
--   this table, through latestSnapshotEv() - ev_pct, date_fin_estimee and
--   faits_marquants. That is not a contradiction. Those three are added later,
--   by V22, and they are not computed figures at all: they are the project
--   manager's own judgement, typed in at the review (how far along the project
--   really is, when he now expects to finish, what happened this month).
--   Nothing in the data can derive them, so the live screen carries the last
--   ones forward instead of showing an empty box between two reviews.
--   Delete this table and the application can only ever show today's numbers;
--   correcting one old timesheet would silently rewrite what last March looked
--   like, and the minutes of a review could never be checked again.
--
-- NOTE FOR THE DEFENCE
--   application.yml sets Hibernate ddl-auto to "validate": JPA never creates
--   or alters a table. Every type and size written below is compared with the
--   entity at startup, and a mismatch stops the application immediately.
-- =============================================================

CREATE TABLE snapshot_kpis (
    -- BIGSERIAL = an 8-byte integer plus a sequence that fills it by itself.
    -- Why: the application never has to invent an id, and two users saving at
    -- the same moment cannot end up with the same number.
    id                BIGSERIAL     PRIMARY KEY,
    -- Which project this photo belongs to. NOT NULL because a snapshot with no
    -- project is meaningless: nobody could say whose figures these are.
    project_id        BIGINT        NOT NULL,
    -- The day the photo was taken. A DATE and not a TIMESTAMP because a review
    -- is a working day, not an instant - and because a plain date is what makes
    -- the "one snapshot per project per day" rule below possible.
    snapshot_date     DATE          NOT NULL,
    -- Planned cost: every planned day of the project turned into money
    -- (days x daily rate x TCC) and summed by KpiService, then copied here.
    budget_planifie   NUMERIC(15,2),
    -- Cost already consumed: the same computation over the validated timesheets.
    -- NUMERIC(15,2) and never a floating point type: NUMERIC is exact, so
    -- 0.1 + 0.2 really is 0.3. With a float, a total built from thousands of
    -- lines drifts by a few cents and the margin never matches the accounting.
    budget_consome    NUMERIC(15,2),
    -- EAC = "Estimate At Completion": what the project will have cost in the
    -- end = cost already consumed + the part of the plan not consumed yet.
    eac               NUMERIC(15,2),
    -- Expected margin = project budget (converted into TND) - EAC.
    -- These four money columns are nullable on purpose: a project can exist
    -- before its budget is known, and KpiService then stores NULL rather than a
    -- misleading 0. An empty cell says "not known yet"; a 0 would be read at the
    -- review as "no margin left" and would raise a false alarm.
    marge             NUMERIC(15,2),
    -- Consumption rate, stored as a RATIO and not as a percentage:
    -- e.g. 0.7532 means 75.32 %. The frontend does the x 100.
    -- Why NUMERIC(7,4), that is 4 digits after the point: the screen shows the
    -- percentage with 2 decimals, so the stored ratio needs 2 more digits to
    -- stay exact. With only 2 decimals, 75.32 % would be stored as 0.75 and
    -- would come back on screen as 75.00 %.
    taux_consommation NUMERIC(7,4),       -- e.g. 0.7532 = 75.32 %
    -- created_at / updated_at / deleted are the three columns every table of
    -- PMS carries; on the Java side they are inherited from BaseEntity. Spring
    -- auditing fills the two dates when a row is saved, and DEFAULT NOW() is the
    -- safety net that keeps a plain SQL INSERT (the demo seed of V26) legal.
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    -- Soft delete: PMS never really erases a row, it sets this flag to TRUE and
    -- every query filters on deleted = FALSE.
    -- Why: a financial history that can be erased is worth nothing during an
    -- audit. Without it, one wrong click would destroy the record of a review
    -- for good.
    deleted           BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Foreign key: project_id must point at a real row of "projects".
    -- Why: it is the database itself, not the Java code, that refuses an orphan
    -- snapshot. Without it, a project removed straight in SQL would leave rows
    -- here pointing at nothing and the KPI screen would crash while reading
    -- project.code. There is no ON DELETE clause, so the default applies:
    -- PostgreSQL REFUSES to delete a project that still has snapshots - which is
    -- what we want, since a project is only ever soft-deleted anyway.
    CONSTRAINT fk_kpi_project FOREIGN KEY (project_id) REFERENCES projects(id)
);

-- WHAT: at most one LIVE snapshot per project and per day.
-- WHY : two photos of the same project on the same day would both claim to be
--       "the figures of that review", and KpiService.latestSnapshotEv(), which
--       sorts on snapshot_date to find the most recent EV estimate, would pick
--       one of the two at random - so the CA production and the current margin
--       of the live screen would depend on which row was chosen.
-- WHY THE "WHERE deleted = FALSE" PART (this makes it a PARTIAL index: the
--       index only covers the rows that match the condition): a snapshot is
--       soft-deleted, so the old row physically stays in the table. Without the
--       WHERE clause, deleting today's snapshot and creating a corrected one
--       would be refused with "duplicate key value violates unique constraint",
--       and the user could never fix a mistake made the same day.
CREATE UNIQUE INDEX uk_kpi_project_date ON snapshot_kpis(project_id, snapshot_date) WHERE deleted = FALSE;
-- WHAT: a plain lookup index for "give me every live snapshot of project 42",
--       which is the query behind the KPI history tab
--       (SnapshotKpiRepository.findActiveByProjectId).
-- WHY : without an index PostgreSQL reads the whole table and filters row by
--       row. On a database holding years of reviews for every project of the
--       company, opening the history tab of one project would get slower and
--       slower for no reason the user can see.
CREATE INDEX        idx_kpi_project     ON snapshot_kpis(project_id)               WHERE deleted = FALSE;

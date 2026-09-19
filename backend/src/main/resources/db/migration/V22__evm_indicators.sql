-- =============================================================
-- V22 : EVM indicators (spec F-AFF-13 §5 — official glossary)
-- (French original title: "Indicateurs EVM")
-- -------------------------------------------------------------
-- WHAT THIS FILE IS
-- A Flyway migration. Flyway is the tool that plays the .sql files of this
-- folder against the database, one after the other in version order, and
-- writes down which ones it has already played. So a file like this one runs
-- exactly once per database and is never edited afterwards; a correction is
-- always a new file with a higher number.
--
-- This migration adds the "Situation actuelle" (current situation) block of the
-- monthly project review to the KPI snapshot table, plus one baseline figure on
-- the project sheet.
--
-- EVM = "Earned Value Management": the standard way to measure a project by the
-- value of the work really done, and not by the money already spent.
--
-- WHERE IT SITS IN THE FLOW
--   V8 created snapshot_kpis (planned budget, spent budget, EAC, margin, burn
--   rate). V22 extends that same table instead of creating a second one, so one
--   review is still one row.
--   Java side: com.pms.kpi.entity.SnapshotKpi has one field per column added
--   here, and com.pms.kpi.service.KpiService computes each figure and then
--   freezes it into the row. The project manager only types three things
--   (see com.pms.kpi.dto.SnapshotRequest): ev_pct, date_fin_estimee and
--   faits_marquants. Everything else below is calculated by the server.
--
-- WHY IT EXISTS
-- Delete this file and snapshot_kpis only holds money. The review screen
-- required by F-AFF-13 could then show no progress, no drift in man-days, and
-- no revenue that is earned but not yet invoiced. There would also be no
-- month-by-month history: each review would simply overwrite the one before.
-- =============================================================

-- ALTER TABLE ... ADD COLUMN extends the existing table; the rows already
-- stored keep their values and get NULL in the new columns.
-- Why IF NOT EXISTS on every column: it makes the statement safe to replay.
-- Flyway normally runs the file once, but a database restored from a dump taken
-- in the middle of this migration, or a developer replaying the file by hand,
-- would otherwise stop on "column already exists" and leave the table
-- half-built.
-- Why every column is nullable: a snapshot written before this migration has no
-- EVM values, and a project whose manager has not yet stated the EV % honestly
-- has none either. NULL means "not measured" and the screen prints "—".
-- A NOT NULL DEFAULT 0 would have meant "measured, and the answer is zero", so
-- a healthy project would be displayed as zero progress and zero margin.
-- Why NUMERIC everywhere and never FLOAT: NUMERIC is exact decimal arithmetic.
-- Money computed with binary floats drifts (0.1 + 0.2 gives 0.30000000000000004),
-- and these amounts are shown to a client and compared with invoices.
ALTER TABLE snapshot_kpis
    -- Earned Value progress, in percent. Entered by hand by the project manager
    -- (CdP): it is a judgement about how much of the work is really done, and no
    -- query can produce it. NUMERIC(5,2) = 5 digits of which 2 after the point,
    -- so up to 999.99; the application keeps it inside 0-100.
    -- It is the root of the money block below: ca_production, fae, marge_actuelle
    -- are all derived from it, which is why KpiService leaves those three empty
    -- (and warns the user) when ev_pct is NULL.
    ADD COLUMN IF NOT EXISTS ev_pct             NUMERIC(5,2),   -- Earned Value progress, typed by the project manager (0-100)
    -- Deliverables handed over or accepted, divided by deliverables planned,
    -- times 100. This is the only progress figure the system can compute alone.
    -- Why keep it next to ev_pct instead of replacing it: when the two disagree
    -- strongly (EV 80 %, delivery 20 %) the review has a question to ask.
    ADD COLUMN IF NOT EXISTS delivery_pct       NUMERIC(5,2),   -- delivered / planned × 100
    -- Man-days already charged to the project (JH = "jour-homme", one person for
    -- one day), i.e. the sum of the validated time entries.
    ADD COLUMN IF NOT EXISTS consomme_jh        NUMERIC(10,2),  -- sum of the time entries
    -- RAF = "reste à faire", the man-days still to do, read from the workload
    -- plan of the months that have not happened yet.
    ADD COLUMN IF NOT EXISTS raf_jh             NUMERIC(10,2),  -- work remaining, from the workload plan
    -- Drift = man-days SOLD to the client − consumed − remaining. A negative
    -- number is the alarm: the project will need more days than were sold, so the
    -- margin is being eaten. Note the reference is the sold workload, not the
    -- internal plan: the plan can be revised at any moment by the manager, and
    -- measuring against it would make the drift vanish exactly when it appears.
    ADD COLUMN IF NOT EXISTS derive_jh          NUMERIC(10,2),  -- sold workload − consumed − remaining
    -- Production revenue = contract total in TND × EV %. In plain words: if the
    -- manager says the project is 40 % done, then 40 % of what the client will
    -- pay has been EARNED, even if no invoice has left yet. This is the "earned
    -- value" of EVM expressed in money. NUMERIC(15,2) because contracts here run
    -- into millions of dinars, and (10,2) would overflow on the biggest ones.
    ADD COLUMN IF NOT EXISTS ca_production      NUMERIC(15,2),  -- budget in TND × EV %
    -- Sum of the billing milestones already invoiced or already paid, converted
    -- into TND. Milestones that are only planned are deliberately excluded: they
    -- are not revenue yet, and counting them would show money the company has no
    -- right to claim.
    ADD COLUMN IF NOT EXISTS total_facture      NUMERIC(15,2),  -- sum of invoiced/paid milestones (TND)
    -- FAE = "facture à établir", invoice still to be issued: revenue that is
    -- earned but not yet billed. It is a real accounting figure, which is why it
    -- is stored in the snapshot and not only displayed.
    ADD COLUMN IF NOT EXISTS fae                NUMERIC(15,2),  -- production revenue − total invoiced
    -- Current margin = production revenue − cost really incurred so far. It uses
    -- the money actually spent, not the EAC forecast: this column answers "where
    -- do I stand today", while the older "marge" column answers "where will I
    -- land at the end".
    ADD COLUMN IF NOT EXISTS marge_actuelle     NUMERIC(15,2),  -- production revenue − cost incurred
    -- The same margin as a ratio. Stored as a fraction on 4 decimals: 0.4412
    -- means 44.12 %. 4 decimals and not 2 because the value is a ratio, not an
    -- amount; rounding it to 0.44 would already move a million-dinar margin by
    -- thousands of dinars when the screen multiplies it back.
    ADD COLUMN IF NOT EXISTS marge_actuelle_pct NUMERIC(7,4),   -- current margin / production revenue
    -- The end date the manager now expects. DATE and not TIMESTAMP on purpose: a
    -- review states a day, never an instant, and a timestamp would drag a time
    -- zone into a figure that has none.
    ADD COLUMN IF NOT EXISTS date_fin_estimee   DATE,
    -- Free text: the highlights of the month (a blocking point, a client
    -- decision, a delivery accepted). It is the only part of the review a jury or
    -- an auditor can read as a sentence, so it is kept with the numbers of the
    -- same month rather than in a separate notes table.
    ADD COLUMN IF NOT EXISTS faits_marquants    VARCHAR(2000);

-- Sold net margin (%): the commercial baseline of the project identification
-- sheet — the margin the company believed it was selling when the contract was
-- signed. The review compares marge_actuelle_pct above against this line.
-- It is typed by hand as a single aggregate figure; when an internal quote (DI)
-- exists for the project, the margin computed line by line from that quote wins
-- over this one, because the DI is built from real cost prices.
-- It lives on projects and not on snapshot_kpis because it is a property of the
-- contract, fixed once, not a measurement repeated every month.
ALTER TABLE projects
    -- Same fraction convention as marge_actuelle_pct: 0.4412 = 44.12 %.
    -- Nullable because a project can exist before anybody has stated its sold
    -- margin; the review then simply shows no baseline instead of showing 0 %,
    -- which would read as a contract sold at cost price.
    ADD COLUMN IF NOT EXISTS marge_nette_vendue NUMERIC(7,4);   -- e.g. 0.4412 = 44.12 %

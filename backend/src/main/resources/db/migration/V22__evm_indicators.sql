-- V22: adds the "Situation actuelle" (current situation) EVM block to snapshot_kpis (spec F-AFF-13 §5), plus
-- one baseline column on projects. EVM = Earned Value Management: measuring a project by value of work done,
-- not money spent. Extends V8's table rather than creating a second one, so one review stays one row.
-- The PM only types ev_pct, date_fin_estimee and faits_marquants (SnapshotRequest); KpiService computes the rest.

-- IF NOT EXISTS on every column makes the statement safe to replay (a dump restored mid-migration, or a
-- developer replaying by hand, would otherwise stop on "column already exists" with a half-built table).
-- All columns nullable: NULL means "not measured" (screen prints "—"); a NOT NULL DEFAULT 0 would display a
-- healthy project as zero progress and zero margin. NUMERIC everywhere, never FLOAT, since these amounts are
-- shown to clients and reconciled against invoices, and binary floats drift (0.1 + 0.2 != 0.3 exactly).
ALTER TABLE snapshot_kpis
    -- Earned Value progress, typed by the PM (a judgement, not something a query can produce). Root of the
    -- money block below - KpiService leaves ca_production/fae/marge_actuelle empty when this is NULL.
    ADD COLUMN IF NOT EXISTS ev_pct             NUMERIC(5,2),   -- Earned Value progress, typed by the project manager (0-100)
    -- Deliverables handed over/accepted over planned x100 - the only progress figure computed automatically.
    -- Kept alongside ev_pct rather than replacing it: a strong disagreement between the two is a real question.
    ADD COLUMN IF NOT EXISTS delivery_pct       NUMERIC(5,2),   -- delivered / planned × 100
    -- Man-days already charged (JH), sum of validated time entries.
    ADD COLUMN IF NOT EXISTS consomme_jh        NUMERIC(10,2),  -- sum of the time entries
    -- RAF ("reste à faire"): man-days still to do, from the workload plan's future months.
    ADD COLUMN IF NOT EXISTS raf_jh             NUMERIC(10,2),  -- work remaining, from the workload plan
    -- Sold workload − consumed − remaining; negative means the project needs more days than were sold. Measured
    -- against the sold workload, not the revisable internal plan, so the drift can't be hidden by replanning.
    ADD COLUMN IF NOT EXISTS derive_jh          NUMERIC(10,2),  -- sold workload − consumed − remaining
    -- Contract total in TND x EV% - the "earned value" of EVM in money, even before any invoice is issued.
    -- NUMERIC(15,2): contracts here run into millions of dinars.
    ADD COLUMN IF NOT EXISTS ca_production      NUMERIC(15,2),  -- budget in TND × EV %
    -- Sum of milestones already invoiced/paid, in TND; purely planned milestones are excluded on purpose.
    ADD COLUMN IF NOT EXISTS total_facture      NUMERIC(15,2),  -- sum of invoiced/paid milestones (TND)
    -- FAE ("facture à établir"): revenue earned but not yet billed - a real accounting figure, hence stored.
    ADD COLUMN IF NOT EXISTS fae                NUMERIC(15,2),  -- production revenue − total invoiced
    -- Current margin = production revenue − cost incurred so far (vs. the older "marge" column, which is the
    -- EAC-based forecast of where the project will land).
    ADD COLUMN IF NOT EXISTS marge_actuelle     NUMERIC(15,2),  -- production revenue − cost incurred
    -- Same margin as a ratio, 4 decimals (0.4412 = 44.12%) since rounding to 2 would move a million-dinar
    -- margin by thousands once the screen multiplies it back.
    ADD COLUMN IF NOT EXISTS marge_actuelle_pct NUMERIC(7,4),   -- current margin / production revenue
    -- Manager's current end-date estimate. DATE not TIMESTAMP: a review states a day, not an instant.
    ADD COLUMN IF NOT EXISTS date_fin_estimee   DATE,
    -- Free-text monthly highlights; kept with this month's numbers rather than in a separate notes table.
    ADD COLUMN IF NOT EXISTS faits_marquants    VARCHAR(2000);

-- Sold net margin (%): the commercial baseline from the identification sheet, compared against
-- marge_actuelle_pct above. Overridden by the DI's line-by-line margin when one exists (real cost prices win).
-- Lives on projects, not snapshot_kpis, since it's fixed once at signing, not remeasured monthly.
ALTER TABLE projects
    -- Nullable so an unstated baseline shows as no baseline, not a misleading 0% (sold at cost).
    ADD COLUMN IF NOT EXISTS marge_nette_vendue NUMERIC(7,4);   -- e.g. 0.4412 = 44.12 %

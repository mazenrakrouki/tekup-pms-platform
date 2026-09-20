-- V15: adds workload_days to "avenants" (contract amendments), matching the Excel line "Workload avenants en JH"
-- (JH = person-days). The avenants table itself was created by V9, money side only (column montant).
--
-- Unlike montant, this column is only recorded and displayed, not wired into the KPI calculation: nothing adds
-- it to projects.sold_workload_days (V14), and KpiService's derive_jh reads that project column, not this one.
-- montant IS applied automatically by AvenantService (adds/subtracts projects.revised_budget on create/delete).

-- ALTER TABLE ... ADD COLUMN keeps existing amendment rows; IF NOT EXISTS makes it safe to replay if the
-- column was already added by hand (a failing migration would otherwise block the whole app start-up).
--
-- NUMERIC(10,2): half-days (12.50 JH) are normal in this business. NUMERIC and not DOUBLE PRECISION, since a
-- binary double can't hold 0.1 exactly and a long project's amendment total would drift from what's on screen.
-- Java side must match (Avenant.workloadDays, precision=10/scale=2): ddl-auto=validate stops boot on mismatch.
--
-- No NOT NULL/DEFAULT: existing amendments have no known value, and 0.00 (price-only change) must stay
-- distinguishable from NULL (nobody filled it in) on the billing screen.
-- May be NEGATIVE too: an amendment removing work from scope is a real case, so no CHECK >= 0 here.
ALTER TABLE avenants
    ADD COLUMN IF NOT EXISTS workload_days NUMERIC(10,2);

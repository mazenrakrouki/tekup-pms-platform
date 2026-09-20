-- V14: adds the eleven "Fiche identification" columns to projects (BUSINESS_ANALYSIS §9 sheet 2, ADR-002),
-- completing the Project model to match the company's Excel workbook exactly, currency stored on the row.
-- All nullable except the two with a DEFAULT: the sheet is filled in progressively as contract paperwork lands.

-- IF NOT EXISTS is repeated on every clause (not written once) since the option belongs to each ADD COLUMN.
-- Defensive style used by every ALTER TABLE migration here (V15, V16, V19, V22): protects a database where a
-- column was already added by hand (ADR-019) - without it, a failed migration would block every later release.
-- One statement, one semicolon: PostgreSQL rewrites the table once instead of taking the lock eleven times.
ALTER TABLE projects
    -- Client's own contract reference; text since it may contain letters/slashes, not a PMS-assigned number.
    ADD COLUMN IF NOT EXISTS contract_id               VARCHAR(100),
    -- Name of the client who signed the contract.
    ADD COLUMN IF NOT EXISTS client                    VARCHAR(255),
    -- The body actually paying (e.g. a development bank on public work), kept apart from client because the
    -- two can be different organisations with different reporting rules. Empty on an ordinary private contract.
    ADD COLUMN IF NOT EXISTS funder                    VARCHAR(255),       -- funder / donor ("bailleur de fonds")
    -- Solo (SEUL) or consortium (GROUPEMENT), mapped by enum BusinessModel, stored as text so a later enum
    -- value insertion can't change the meaning of rows already saved.
    ADD COLUMN IF NOT EXISTS business_model            VARCHAR(20),        -- SEUL | GROUPEMENT
    -- Fixed price (FORFAIT) or time-and-materials (REGIE), enum EngagementType. Matters beyond the form: on a
    -- FORFAIT an overrun eats the company's margin, on a REGIE the client pays the days actually spent.
    ADD COLUMN IF NOT EXISTS engagement_type           VARCHAR(20),        -- FORFAIT | REGIE
    -- Contract currency code (TND/EUR/FCFA); every amount of this project is expressed in it, not TND, unless
    -- the column name says tnd. DEFAULT 'TND' is required for the ALTER to succeed on a non-empty table.
    ADD COLUMN IF NOT EXISTS currency                  VARCHAR(10) NOT NULL DEFAULT 'TND',
    -- Dinars per unit of that currency; amount in TND = amount x this rate. Stored on the project rather than
    -- fetched live so the reporting stays stable for the life of the contract, matching the Excel sheet.
    -- NUMERIC(15,6): 1 FCFA ~ 0.005850 TND, which two decimals would round away. DEFAULT 1 needs no special case.
    ADD COLUMN IF NOT EXISTS exchange_rate_to_tnd      NUMERIC(15,6) NOT NULL DEFAULT 1,
    -- Share of the budget on licences/subcontracting, in project currency; passed straight to a third party so
    -- tracked apart from in-house days. Financial: ProjectResponse.withoutFinancials() blanks it (BR-050).
    ADD COLUMN IF NOT EXISTS license_subcontract_budget NUMERIC(15,2),
    -- Total workload sold, in man-days (JH); KpiService compares it with days actually booked to show drift.
    -- Stays visible without VIEW_KPI: BR-050 hides money, not a day count.
    ADD COLUMN IF NOT EXISTS sold_workload_days        NUMERIC(10,2),      -- sold workload (man-days)
    -- Man-days reserved for the warranty period. Kept separate from sold_workload_days: mixing them would make
    -- a project look like it still has build budget left, when it's really reserved for fixing.
    ADD COLUMN IF NOT EXISTS warranty_workload_days    NUMERIC(10,2),      -- warranty workload (man-days)
    -- Money reserved for contractual late-delivery penalties. Financial, blanked by withoutFinancials() too.
    ADD COLUMN IF NOT EXISTS penalty_provision         NUMERIC(15,2);      -- PPP
-- Unlike V8-V11's tables, no CHECK constraint here: business_model/engagement_type rely on the Java enums only,
-- and nothing at DB level stops exchange_rate_to_tnd from being 0 - recorded as known gaps, not omissions,
-- since Flyway forbids editing an applied migration.

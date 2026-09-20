-- V21: creates tcc_annuels - one row says "for this person, in this year, one man-day (JH) is billed at this
-- daily rate with this TCC coefficient" (spec F-AFF-13 §6.3 rule 4). TCC is the loaded-cost multiplier the
-- company adds to a daily rate (social charges, office, tooling): cost of a day = daily_rate x (1 + tcc_rate).
--
-- Rates are renegotiated yearly, but resources.daily_rate/tcc_rate (V3) held only one rate per person, so a
-- multi-year project had all its days re-priced at today's rate - moving the margin and breaking reconciliation
-- with accounting. A yearly row here is NOT mandatory: when none exists for a given year, KpiService falls back
-- to the resource's base rate, so this table holds only the exceptions.
--
-- Written via PUT /api/resources/{id}/tcc -> ResourceService.replaceTccAnnuels (MANAGE_RESOURCES, V24 also
-- grants it to DIRECTEUR). Read by KpiService, which loads every member's yearly rates in one query and prices
-- each charged day by its own year - cost is recomputed at read time, never stored, so a rate fix here
-- immediately corrects every figure derived from it (including the DI, the most sensitive screen).
--
-- A separate table rather than more columns on "resources": the number of years isn't known in advance, and
-- daily_rate_2024/daily_rate_2025-style columns would need a new migration every January.

CREATE TABLE tcc_annuels (
    id          BIGSERIAL     PRIMARY KEY,
    -- Owning person, via resources (the cost sheet), not directly via users.
    resource_id BIGINT        NOT NULL,
    -- Calendar year; no month, rule 4 of F-AFF-13 works at year granularity.
    annee       INT           NOT NULL,
    -- Man-day cost for that year, before the TCC coefficient.
    -- NUMERIC(10,2), never DOUBLE PRECISION: a binary double can't hold 0.1 exactly and totals over hundreds of
    -- lines would drift from what's shown on screen. Java's BigDecimal(10,2) must match (ddl-auto = validate).
    daily_rate  NUMERIC(10,2) NOT NULL,
    -- Overhead coefficient as a fraction (0.4200 = 42%). NUMERIC(5,4) caps it under 10, so typing 42 instead of
    -- 0.42 is rejected by the database rather than silently multiplying every cost by 43.
    tcc_rate    NUMERIC(5,4)  NOT NULL,
    -- Audit columns from BaseEntity, written directly here since V19 (which added them elsewhere) already ran
    -- before this table existed.
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    -- Soft delete: a KPI figure computed last month must stay explainable, so the rate used at the time must
    -- stay readable.
    deleted     BOOLEAN       NOT NULL DEFAULT FALSE,
    -- No ON DELETE clause: the app soft-deletes resources and never hard-deletes one, so this FK only guards
    -- against a manual DELETE leaving rates pointing at nothing. A soft-deleted resource keeps its yearly rates,
    -- which is why TccAnnuelRepository.findActiveByUserIdIn separately filters r.deleted = false.
    CONSTRAINT fk_tcc_annuel_resource FOREIGN KEY (resource_id) REFERENCES resources(id),
    -- Sanity check on a human-typed year (20245 or 202 would silently never match any charge line).
    CONSTRAINT chk_tcc_annee CHECK (annee BETWEEN 2000 AND 2100)
);

-- One live row per (resource, year). Partial unique index (deleted = FALSE only), same pattern as
-- uk_users_email/uk_projects_code (V18): an absolute UNIQUE would make a deleted year's slot unusable forever.
-- Without it, two live rows could give 2024 two different rates, and the query order would decide the cost.
-- ResourceService.replaceTccAnnuels updates an existing year's row in place (not delete+insert) specifically
-- because Hibernate flushes INSERTs before UPDATEs, which would otherwise hit this index as a duplicate (409).
CREATE UNIQUE INDEX uk_tcc_annuel_resource_annee ON tcc_annuels(resource_id, annee) WHERE deleted = FALSE;
-- Speeds up "live rates of this resource" (both TccAnnuelRepository queries start there). Largely redundant
-- with the unique index above (same leading column) but cheap on a table this small, and states the intent
-- separately from the uniqueness rule.
CREATE INDEX        idx_tcc_annuel_resource      ON tcc_annuels(resource_id)        WHERE deleted = FALSE;

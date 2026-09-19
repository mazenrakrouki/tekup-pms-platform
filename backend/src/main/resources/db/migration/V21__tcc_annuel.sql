-- =============================================================
-- V21 : TCC per year (spec F-AFF-13 section 6.3, rule 4)
-- =============================================================
-- WHAT THIS FILE IS
--   It creates the table tcc_annuels. One row says: "for THIS person, in THIS
--   year, one working day is billed at this daily rate and loaded with this
--   TCC coefficient".
--   Two words to define first, because everything below uses them.
--     JH ("jours-homme", person-days): one person working for one day.
--     TCC: the coefficient the company adds on top of a daily rate to get
--          what a working day really costs it - social charges, office,
--          tooling. It is stored as a fraction: 0.4200 means 42 percent, and
--          the cost of a day is daily_rate x (1 + tcc_rate).
--
-- WHY IT EXISTS - the business rule, in one sentence
--   The cost of a man-day depends on the YEAR the day was charged to,
--   because the rates are renegotiated every year: the TCC of 2024 is not the
--   TCC of 2025. Before this table, resources.daily_rate and
--   resources.tcc_rate (V3) held one single rate per person, so a project
--   running over two years had all of its days priced at the rate of today.
--   Days worked in 2024 were re-priced at the 2025 rate, which moves the
--   margin of a long project by several points and makes the figure
--   impossible to reconcile with the accounting.
--
-- THE FALLBACK RULE - the part a jury will test
--   A yearly row is NOT mandatory. When a resource has no row for the year a
--   day belongs to, KpiService falls back to the base rates carried by the
--   resource itself (resources.daily_rate / resources.tcc_rate). So this
--   table holds the exceptions, not the whole history: a person whose rate
--   never moved needs no row here at all.
--
-- WHERE IT SITS IN THE FLOW
--   Writing:
--     PUT /api/resources/{id}/tcc -> ResourceController
--       -> ResourceService.replaceTccAnnuels, which carries
--          @PreAuthorize("hasAuthority('MANAGE_RESOURCES')") on the SERVICE
--          method, never on the controller. V24 later grants that permission
--          to DIRECTEUR as well; a chef de projet keeps VIEW_RESOURCES and
--          can only read.
--       -> TccAnnuel entity -> TccAnnuelRepository -> THIS TABLE.
--   Reading, and this is the one that matters for the figures:
--     KpiService loads every yearly rate of every member of a project in ONE
--     query (TccAnnuelRepository.findActiveByUserIdIn), arranges them as
--     userId -> (year -> rates), and prices each charged day with the row of
--     its own year. A cost is never stored in a column: it is recomputed at
--     every read, so correcting a rate here immediately corrects every figure
--     derived from it - which is also the rule that protects the DI (Devis
--     Interne = internal quote), the most sensitive screen of the
--     application.
--
-- WHY A SEPARATE TABLE RATHER THAN MORE COLUMNS ON "resources"
--   The number of years is not known in advance. Columns such as
--   daily_rate_2024 and daily_rate_2025 would mean a new migration every
--   January, and answering "what did this person cost in year N" would mean
--   building a column name out of a number.
-- =============================================================

CREATE TABLE tcc_annuels (
    -- BIGSERIAL: PostgreSQL generates the number itself, and BIG because it
    -- is a 64-bit counter. The Java side inherits the id from BaseEntity with
    -- @GeneratedValue(strategy = IDENTITY), so the database is the only place
    -- that decides an id and two simultaneous inserts can never collide.
    id          BIGSERIAL     PRIMARY KEY,
    -- The person these rates belong to, through the resources table (the cost
    -- sheet of an employee), not directly through users.
    -- NOT NULL: a yearly rate that belongs to nobody could never be applied to
    -- anything, and would sit in the table for ever.
    resource_id BIGINT        NOT NULL,
    -- The calendar year, for example 2024. There is no month: rule 4 of
    -- F-AFF-13 works at year granularity, and KpiService keeps only
    -- period.getYear() of a charge line when it looks a rate up.
    annee       INT           NOT NULL,
    -- What one man-day of this person costs during that year, before the TCC
    -- coefficient is applied.
    -- NUMERIC(10,2): 10 digits in all, 2 of them after the point, so at most
    -- 99999999.99. NUMERIC and never DOUBLE PRECISION: a double keeps decimal
    -- values in binary and cannot hold 0.1 exactly, so a total built from
    -- hundreds of day costs drifts by a few millimes and the sum shown on a
    -- screen stops matching the lines the user can read. The Java field is a
    -- BigDecimal with precision = 10 and scale = 2 for the same reason, and it
    -- must match: the application runs with ddl-auto = validate, so any
    -- difference stops the start-up instead of silently rounding money later.
    daily_rate  NUMERIC(10,2) NOT NULL,
    -- The overhead coefficient of that year, as a fraction: 0.4200 = 42 %.
    -- NUMERIC(5,4) means 5 digits with 4 of them after the point, so values
    -- from 0.0000 to 9.9999. Two things follow. A coefficient of up to 999
    -- percent stays possible, far more than any real case; and the value 42,
    -- typed by mistake instead of 0.42, simply does not fit, so the error that
    -- would multiply every cost by 43 is refused by the database itself.
    -- resources.tcc_rate (V3) uses the same type, plus the constraint
    -- CHECK (tcc_rate >= 0 AND tcc_rate < 10) for the base rate.
    tcc_rate    NUMERIC(5,4)  NOT NULL,
    -- The four audit columns and the soft-delete flag that every table of this
    -- application carries. They come from the Java parent class BaseEntity:
    -- created_at / updated_at are filled by @CreatedDate / @LastModifiedDate,
    -- created_by / updated_by by @CreatedBy / @LastModifiedBy through
    -- SpringSecurityAuditorAware, which returns the signed-in user or the text
    -- "system" when nobody is signed in. They are written here directly,
    -- inside the CREATE TABLE, because V19 - the migration that added
    -- created_by / updated_by to every table - had already run before this
    -- table existed.
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    -- Soft delete: removing a year from the screen sets this flag to TRUE and
    -- the row stays. Why it matters here more than elsewhere: a KPI figure
    -- computed last month must still be explainable, and that means the rate
    -- that was used at the time has to stay readable in the database.
    deleted     BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Foreign key: resource_id must point at a real row of resources.
    -- There is no ON DELETE clause, so PostgreSQL uses NO ACTION and refuses
    -- to erase a resource that still has yearly rates. That is the wanted
    -- behaviour: the application soft-deletes a resource (deleted = TRUE) and
    -- never erases one, so this constraint is there to stop a manual DELETE
    -- from leaving rates pointing at nothing.
    -- One consequence is worth knowing for a question from the jury: this
    -- constraint says nothing about the "deleted" flag of the resource. A
    -- soft-deleted resource keeps its yearly rates, which is exactly why
    -- TccAnnuelRepository.findActiveByUserIdIn also filters r.deleted = false
    -- in its query - without that filter, the rate of an archived person went
    -- on feeding the cost calculation.
    CONSTRAINT fk_tcc_annuel_resource FOREIGN KEY (resource_id) REFERENCES resources(id),
    -- A cheap sanity check on a number typed by a human. 20245 or 202 instead
    -- of 2024 would produce a year that matches no charge line at all, so the
    -- rate would silently never be used and nobody would ever see an error.
    -- The database refuses the row instead. BETWEEN includes both bounds.
    CONSTRAINT chk_tcc_annee CHECK (annee BETWEEN 2000 AND 2100)
);

-- ONE LIVE ROW PER RESOURCE AND PER YEAR.
-- This is a PARTIAL unique index: the rule applies only to the rows where
-- deleted = FALSE. Several years removed from the screen can therefore keep
-- sitting in the table with the same (resource, year) pair, while a year that
-- is still alive exists exactly once.
-- WHY the partial form and not a plain UNIQUE (resource_id, annee): with an
-- absolute unique rule, a year deleted once could never be entered again for
-- that person, for ever - the dead row would keep the place. It is the same
-- pattern as uk_users_email and uk_projects_code, which V18 converted for
-- exactly that reason.
-- WITHOUT this index at all: two live rows could give 2024 two different daily
-- rates for the same person, and the cost of a project would depend on which
-- of the two rows the query happened to read first.
-- IT ALSO EXPLAINS A DETAIL OF THE JAVA CODE:
-- ResourceService.replaceTccAnnuels updates the row of an existing year in
-- place instead of deleting it and inserting a new one. Hibernate sends its
-- INSERT statements before its UPDATE statements when it flushes, so the new
-- row would arrive before the old one was flagged deleted, and this index
-- would reject it with a duplicate key error (HTTP 409) on every edit of a
-- year that already exists.
CREATE UNIQUE INDEX uk_tcc_annuel_resource_annee ON tcc_annuels(resource_id, annee) WHERE deleted = FALSE;
-- SPEED, for the two queries of TccAnnuelRepository: both of them start with
-- "the live rates of this resource", so PostgreSQL can jump straight to the
-- rows of one resource instead of reading the whole table.
-- Partial again (WHERE deleted = FALSE): the index stays small because it
-- ignores the rows no query ever asks for, and it matches the conditions of
-- those queries exactly.
-- Is it redundant with the unique index above, which begins with the same
-- column? Largely yes - PostgreSQL is able to use the first column of a
-- two-column index on its own. It is kept because it states the intention of
-- the search separately from the uniqueness rule, and because it costs almost
-- nothing on a table that holds a handful of rows per person.
CREATE INDEX        idx_tcc_annuel_resource      ON tcc_annuels(resource_id)        WHERE deleted = FALSE;

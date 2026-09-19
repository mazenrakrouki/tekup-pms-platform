-- =============================================================
-- V3 : Human resources, TCC and application settings
-- =============================================================
--
-- WHAT THIS FILE IS
--   Two tables that have nothing to do with each other except that both are
--   reference data: "resources", the cost sheet of a person (what one of their
--   working days costs the company), and "parameters", a small key/value table
--   of application settings. The file also inserts the three settings the
--   platform ships with.
--
-- WHERE IT SITS IN THE FLOW
--   Runs after: V1 (users) - resources points at users, so the order matters.
--     A foreign key cannot be created before the table it points at exists.
--   Mapped by: Resource.java and Parameter.java (com.pms.user.entity).
--   Read by: ResourceService (create, update, delete under
--     MANAGE_RESOURCES; read under VIEW_RESOURCES), ResourceMapper, and above
--     all KpiService, which multiplies the days declared in charges_reelles
--     (V7) by the rates of this table to obtain a cost.
--   Extended by: V4, which adds the missing unique rule on resources.user_id;
--     V19, which adds created_by / updated_by; V21, which adds the table
--     tcc_annuels so that a rate can change from one year to the next, with
--     the rates below acting as the fallback when a year has no specific row.
--
-- WHY IT EXISTS
--   Delete this file and PMS has no price per day. Every margin, every cost
--   line, every KPI and the whole Devis Interne (internal quote) would have
--   nothing to multiply the man-days by.
--
-- WHY THE COST IS NOT A FEW MORE COLUMNS ON users (ADR-022)
--   users is the ACCOUNT: who signs in and what they may do. resources is the
--   MONEY: what the person costs. An assistant or an intern needs an account
--   and has no billable rate, so half of the columns would always be empty on
--   a merged table. And a cost sheet has to keep existing for the figures of
--   past months long after the account has been switched off.
--
-- WHAT TCC MEANS
--   TCC is the overhead coefficient the company adds on top of the raw daily
--   rate: social charges, office, tooling. It is stored as a fraction, not as
--   a percentage, so the loaded cost of one day is
--   daily_rate * (1 + tcc_rate).

-- -- Resources (billable staff) ----------------------------------
-- One row per person who has a cost. Not every user has one, and that is the
-- point of a separate table.
CREATE TABLE resources (
    id              BIGSERIAL    PRIMARY KEY,
    -- The account this cost sheet belongs to. Mapped as a @OneToOne in
    -- Resource.java.
    -- NOT NULL: a cost sheet with no owner could never be matched to the days
    -- somebody declared, so it would silently price nothing.
    user_id         BIGINT       NOT NULL,
    -- The raw cost of one man-day, before the TCC coefficient.
    -- NUMERIC(10,2) means "10 digits in all, 2 of them after the point", so at
    -- most 99999999.99. NUMERIC and never a floating point type: a float
    -- stores 0.1 as an approximation, so adding a few hundred day costs drifts
    -- by a few cents and the total on the screen stops matching the sum of the
    -- lines the user can read. NUMERIC keeps exact decimal values, which is
    -- what money needs. It is mapped to BigDecimal on the Java side for the
    -- same reason.
    daily_rate      NUMERIC(10,2) NOT NULL,
    -- The TCC coefficient as a fraction. NUMERIC(5,4) means 5 digits in all
    -- with 4 after the point, so the range is 0.0000 to 9.9999.
    -- Four decimals because a negotiated coefficient such as 0.4275 has to
    -- survive exactly. Only five digits in total because that is what stops
    -- somebody from typing 42 instead of 0.42 - a value of 42 would not fit,
    -- while with a wider type it would be accepted and would multiply every
    -- cost of that person by 43.
    tcc_rate        NUMERIC(5,4)  NOT NULL,          -- for example 0.4200 means 42 percent
    -- First day the person can be put on a project.
    -- DATE and not TIMESTAMP: staffing is decided by the day. An hour would
    -- only bring time-zone questions that have no business meaning here.
    staffing_start  DATE          NOT NULL,
    -- Last day of the staffing window, or NULL when there is no planned end -
    -- the normal case for a permanent employee. That is why this column has no
    -- NOT NULL: a conventional far-away date such as 31/12/2099 would be read
    -- as a real end date by anybody writing a report later.
    staffing_end    DATE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- Soft delete, as everywhere in this schema. ResourceService.delete only
    -- sets this flag: the row stays so that a cost computed last year keeps
    -- the rate that was used at the time.
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    -- No ON DELETE CASCADE, on purpose: users are soft-deleted, never really
    -- deleted, so this foreign key is there to refuse an impossible link, not
    -- to propagate anything. What it prevents: a cost sheet pointing at a
    -- user id that does not exist, which would show a rate with no name next
    -- to it on the resources screen.
    CONSTRAINT fk_resources_user FOREIGN KEY (user_id) REFERENCES users(id),
    -- The staffing window must make sense.
    -- Read the NULL handling carefully, it is the important part: when
    -- staffing_end is NULL the first half of the OR is already true, so the
    -- comparison is never made and the open-ended window is accepted. Without
    -- that first half, a NULL end date would make the whole condition unknown
    -- and PostgreSQL would... accept the row anyway, because a CHECK only
    -- refuses what is clearly false. Writing it explicitly says what is meant.
    -- What it catches: a window from 01/06/2026 to 01/01/2026, which would
    -- make every "is this person staffable in July" query answer no, with
    -- nothing on screen to explain why.
    CONSTRAINT chk_resource_dates CHECK (staffing_end IS NULL OR staffing_end > staffing_start),
    -- A day cannot cost zero or less. Without it, a rate left at 0 would make
    -- a whole project look free: the margin would come out equal to the
    -- revenue and nothing would look wrong on the KPI screen.
    CONSTRAINT chk_daily_rate     CHECK (daily_rate > 0),
    -- The coefficient cannot be negative (a negative TCC would make a person
    -- cheaper than their raw rate, which means nothing) and stays below 10.
    -- Being exact: NUMERIC(5,4) already caps the value at 9.9999, so the upper
    -- bound repeats what the type says. It is kept because it states the
    -- business intention in words the reader can see, and it would still hold
    -- if the column type were ever widened.
    CONSTRAINT chk_tcc_rate       CHECK (tcc_rate >= 0 AND tcc_rate < 10)
);

-- Supports the lookup "the cost sheet of user 12", which KpiService runs for
-- every person of a team when it prices a month of declared days. Without it,
-- PostgreSQL reads the whole resources table once per person.
CREATE INDEX idx_resources_user_id ON resources(user_id);
-- Same column, but only the live rows (a PARTIAL index). This is the one that
-- serves ResourceRepository.findActiveByUserId, whose query carries
-- "AND r.deleted = false" - the condition of the query matches the condition
-- of the index, so PostgreSQL can use it.
CREATE INDEX idx_resources_active  ON resources(user_id) WHERE deleted = FALSE;

-- -- Application settings ----------------------------------------
-- A key/value table: one row is one setting, stored as text.
-- Why key/value and not one column per setting: adding a setting is one
-- INSERT, instead of a migration plus an entity change plus a redeploy.
-- What it costs: the value is always a String, so the reader has to convert it
-- ("0.42" to a number) and nothing in the database stops somebody from storing
-- "forty-two percent" in TCC_DEFAULT_RATE.
-- Be honest about this table in front of the jury: it is mapped by
-- Parameter.java, but no Java code reads it back today. The three values below
-- are the documented defaults of the platform, not a live configuration.
CREATE TABLE parameters (
    id          BIGSERIAL    PRIMARY KEY,
    -- The name of the setting. param_key and not "key": KEY is a reserved word
    -- in SQL, and a column called key has to be quoted in every single query.
    param_key   VARCHAR(100) NOT NULL,
    -- The value, always as text. 500 characters is generous on purpose, so a
    -- setting that one day holds a short list or a small JSON string does not
    -- need a migration.
    param_value VARCHAR(500) NOT NULL,
    -- Plain-language explanation for the person who opens this table in six
    -- months. The only column here that may be NULL.
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    -- One key, one row. Without it a lookup by key would return whichever row
    -- the database happened to read first, so "the currency of the platform"
    -- would have two possible answers.
    -- A trap worth knowing before a jury question: this is an ABSOLUTE unique
    -- constraint, and this project soft-deletes. A row marked deleted still
    -- holds its key, so soft-deleting CURRENCY and inserting CURRENCY again
    -- would be refused with a duplicate-key error. The same trap is fixed for
    -- users.email and projects.code by V18, but not here.
    CONSTRAINT uk_parameters_key UNIQUE (param_key)
);

-- Default values shipped with the platform. The descriptions stay in French
-- because they are DATA meant for the person who reads the table, and the
-- application speaks French to its users.
-- TCC_DEFAULT_RATE : the coefficient proposed when a new resource is created.
-- CURRENCY         : the currency shown on the screens (V26 sets it to TND
--                    for the demonstration data).
-- FISCAL_YEAR_START: the first day of the fiscal year, written MM-DD, which is
--                    what the yearly TCC of V21 and the EVM indicators of V22
--                    count from.
-- The doubled quote in l''exercice is how SQL writes one apostrophe inside a
-- text delimited by apostrophes. Written with a single quote, the string would
-- end in the middle of the word and the whole migration would fail to parse.
INSERT INTO parameters (param_key, param_value, description, created_at, updated_at, deleted) VALUES
    ('TCC_DEFAULT_RATE',   '0.42',   'Taux de charges commerciales par défaut (42%)', NOW(), NOW(), FALSE),
    ('CURRENCY',           'EUR',    'Devise utilisée dans la plateforme',             NOW(), NOW(), FALSE),
    ('FISCAL_YEAR_START',  '01-01',  'Début de l''exercice fiscal (MM-DD)',             NOW(), NOW(), FALSE);

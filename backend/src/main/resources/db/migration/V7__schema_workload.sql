-- V7: workload — plan_charges (what was PLANNED for a person on a month) and
-- charges_reelles (what that person actually WORKED, then validated). Two
-- tables and not one with a "type" column, because a declaration goes through
-- an approval step (submitted_at/validated_at/validated_by) that means nothing
-- for a plan — and comparing plan vs reality would mean joining a table with itself.
--
-- Every row is identified by (project_id, user_id, period), where period is a
-- DATE always holding the 1st of the month (e.g. March 2026 = 2026-03-01).
-- A DATE sorts/compares naturally and maps to one LocalDate in Java, unlike a
-- year+month pair. The "always day 1" rule is enforced in the Java services
-- (LocalDate.of(year, month, 1)), not by a CHECK here.

-- -- Planned workload --------------------------------------------
CREATE TABLE plan_charges (
    id           BIGSERIAL    PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    period       DATE         NOT NULL,   -- always the 1st day of the month
    -- NUMERIC(5,2): half/quarter days are the normal unit here; NUMERIC (not
    -- float) so summed totals never drift from what the screen shows.
    planned_days NUMERIC(5,2) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- Soft delete: keeps history for indicators computed while the row existed.
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    -- No ON DELETE CASCADE (unlike V6's team_assignments): a charge line is an
    -- accounting fact and must survive; a real project DELETE should be refused,
    -- not silently take past months' figures with it.
    CONSTRAINT fk_pc_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_pc_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    -- "> 0" here, vs ">= 0" on charges_reelles below: planning zero days is the
    -- same as not planning at all, while declaring zero days worked is a real
    -- statement. Upper bound catches e.g. 220 typed instead of 22.
    CONSTRAINT chk_pc_days   CHECK (planned_days > 0 AND planned_days <= 31)
);

-- Partial unique index: one ACTIVE plan line per (project, user, month), same
-- pattern as uk_ta_project_user_active in V6 — a removed line doesn't block a
-- new one for the same month. The Java side also checks this first
-- (existsByProjectIdAndUserIdAndPeriodAndDeletedFalse) for a readable error
-- message; this index is what makes it true under concurrent saves.
CREATE UNIQUE INDEX uk_pc_active     ON plan_charges(project_id, user_id, period) WHERE deleted = FALSE;
-- Backs "the whole plan of project X" (planning screen, EVM planned value).
CREATE INDEX        idx_pc_project   ON plan_charges(project_id) WHERE deleted = FALSE;
-- Backs "everything planned for this person" across projects.
CREATE INDEX        idx_pc_user      ON plan_charges(user_id)    WHERE deleted = FALSE;

-- -- Actual workload ---------------------------------------------
-- Same shape as plan_charges, plus the three columns of the approval step.
CREATE TABLE charges_reelles (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    period        DATE         NOT NULL,   -- always the 1st day of the month
    -- Same NUMERIC(5,2) as planned_days so the two compare/subtract cleanly.
    actual_days   NUMERIC(5,2) NOT NULL,
    -- Nullable: the row exists as soon as saved; declaring is a later step.
    submitted_at  TIMESTAMP,
    -- Both NULL until someone holding VALIDATE_WORKLOAD approves the line;
    -- that's what the workload screens filter on.
    validated_at  TIMESTAMP,
    -- The approver — distinct from user_id (who did the work) and from V19's
    -- created_by/updated_by (who touched the row at all). Without this
    -- separation, "who approved this month" would have no answer.
    validated_by  BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- No cascade anywhere, same reasoning as plan_charges above.
    CONSTRAINT fk_cr_project      FOREIGN KEY (project_id)  REFERENCES projects(id),
    CONSTRAINT fk_cr_user         FOREIGN KEY (user_id)     REFERENCES users(id),
    -- Nullable FK: points at nothing until the line is approved.
    CONSTRAINT fk_cr_validated_by FOREIGN KEY (validated_by) REFERENCES users(id),
    -- ">= 0" (vs "> 0" on plan_charges): zero days worked is a valid statement
    -- ("assigned but didn't work this month"). Negative would silently reduce
    -- consumed cost and inflate the margin.
    CONSTRAINT chk_cr_days        CHECK (actual_days >= 0 AND actual_days <= 31)
);

-- Same partial unique pattern as the plan side. Here a duplicate is more
-- serious: two declarations for the same month would double the consumed
-- cost and understate the margin shown to the director.
CREATE UNIQUE INDEX uk_cr_active   ON charges_reelles(project_id, user_id, period) WHERE deleted = FALSE;
-- Backs "everything declared on project X" (consumed cost, margin, EVM actual cost).
CREATE INDEX        idx_cr_project ON charges_reelles(project_id) WHERE deleted = FALSE;
-- Backs the developer's personal workload screen.
CREATE INDEX        idx_cr_user    ON charges_reelles(user_id)    WHERE deleted = FALSE;

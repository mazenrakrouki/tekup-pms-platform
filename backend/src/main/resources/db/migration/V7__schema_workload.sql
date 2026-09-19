-- =============================================================
-- V7 : Workload - planned and actual
-- =============================================================
--
-- WHAT THIS FILE IS
--   Creates the two tables that hold the man-days of the project: what was
--   PLANNED for a person on a month (plan_charges) and what that person
--   actually WORKED on that month (charges_reelles).
--
-- WHERE IT SITS IN THE FLOW
--   Runs after: V1 (users) and V5 (projects) - both tables point at them.
--   Mapped by: PlanCharge.java and ChargeReelle.java
--     (com.pms.workload.entity).
--   Written by: PlanChargeService (planning) and ChargeReelleService
--     (declaration and validation). Their methods carry the permissions
--     SUBMIT_WORKLOAD, VALIDATE_WORKLOAD and VIEW_WORKLOAD with @PreAuthorize
--     on the SERVICE, and, because every one of their URLs looks like
--     /api/projects/{id}/**, ProjectScopeInterceptor also checks the perimeter
--     (ADR-021). Permission alone would let a developer declare days on a
--     project he has nothing to do with.
--   Read by: KpiService, which multiplies these days by the rates of the
--     resources table (V3, plus the yearly rates of V21) to obtain a cost, and
--     by the EVM indicators of V22 - planned days give the planned value,
--     actual days give the cost really consumed.
--   Extended by: V19 (created_by / updated_by).
--
-- WHY IT EXISTS
--   Delete these two tables and PMS has no measure of the work. There would be
--   no consumed cost, no margin, no progress, and every indicator of the KPI
--   and EVM screens would have nothing to compute from.
--
-- WHY TWO TABLES AND NOT ONE WITH A "type" COLUMN
--   They look alike, and they are not the same thing. A plan is a decision
--   taken in advance by the project manager; a declaration is a statement made
--   afterwards by the person who did the work, and it goes through a second
--   step - somebody has to approve it. That second step needs three columns
--   (submitted_at, validated_at, validated_by) that mean nothing for a plan.
--   With one merged table those three columns would be empty on half the rows,
--   the unique rule would have to include the type, and every query would
--   start by filtering on it. Above all, comparing plan and reality - which is
--   the whole point of the EVM screen - would mean joining a table with
--   itself.
--
-- THE SHAPE OF THE DATA: one row per project, per person, per month
--   The three columns project_id, user_id and period identify a row. The
--   period is a DATE that always holds the FIRST day of the month, so
--   "March 2026" is stored as 2026-03-01.
--   Why a DATE and not a year column plus a month column: one column sorts and
--   compares naturally ("every month between January and June" is one BETWEEN),
--   and it maps to a single LocalDate in Java. Two integer columns would need
--   a compound comparison in every query, and the classic bug of year 2026
--   month 1 being considered smaller than year 2025 month 12.
--   WHO KEEPS THE RULE: the Java services, not the database. Both
--   PlanChargeService and ChargeReelleService build the value with
--   LocalDate.of(year, month, 1), so the day is always 1. There is no CHECK
--   below that enforces it.

-- -- Planned workload --------------------------------------------
-- What the project manager expects from a person for a given month.
CREATE TABLE plan_charges (
    id           BIGSERIAL    PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    period       DATE         NOT NULL,   -- always the 1st day of the month
    -- The planned man-days for that person on that month.
    -- NUMERIC(5,2) means 5 digits with 2 after the point: half-days and
    -- quarter-days are the normal unit of this business, so an integer column
    -- would force everything to be rounded and a month planned at 10.5 days
    -- would be stored as 10 or 11.
    -- NUMERIC and not a floating point type, for the same reason as the money
    -- columns of V3: adding many approximate values drifts, and the total of a
    -- project would stop matching the sum of the lines shown on screen.
    planned_days NUMERIC(5,2) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- Soft delete: removing a plan line keeps it for the history of the
    -- indicators that were computed while it existed.
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    -- No ON DELETE CASCADE on either key, unlike team_assignments in V6. The
    -- asymmetry is intentional: a charge line is an accounting fact. It must
    -- survive, and a real DELETE on the project must be REFUSED rather than
    -- quietly take the figures of the past months away with it.
    CONSTRAINT fk_pc_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_pc_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    -- A planned line must carry a real amount of work, and a month cannot hold
    -- more than 31 days.
    -- Note the "> 0" and compare it with chk_cr_days further down, which
    -- accepts 0. The difference is deliberate: planning somebody for zero days
    -- is the same as not planning them at all, and the empty row would only
    -- clutter the screen. Declaring zero days actually worked, on the other
    -- hand, is a real statement.
    -- What the upper bound catches: 220 typed instead of 22, which would show
    -- a person consuming ten months of budget in one month and would make
    -- every indicator of that project meaningless.
    CONSTRAINT chk_pc_days   CHECK (planned_days > 0 AND planned_days <= 31)
);

-- One ACTIVE plan line per project, per person, per month.
-- It is a PARTIAL unique index (only the rows with deleted = FALSE), so a line
-- that was removed does not block the creation of a new one for the same
-- month - the same pattern as uk_ta_project_user_active in V6.
-- WHAT IT PREVENTS: two plan lines for Ahmed on project 7 in March. The
-- planned total of the project would count him twice, the EVM planned value
-- would be wrong, and nothing on the screen would show which of the two rows
-- is the real one.
-- The Java side tests the same thing first
-- (existsByProjectIdAndUserIdAndPeriodAndDeletedFalse) so the user gets a
-- readable message instead of a database error; this index is what makes the
-- rule true even when two people save at the very same moment.
CREATE UNIQUE INDEX uk_pc_active     ON plan_charges(project_id, user_id, period) WHERE deleted = FALSE;
-- Supports "the whole plan of project 7", the query behind the planning screen
-- and behind the planned value of the EVM indicators.
CREATE INDEX        idx_pc_project   ON plan_charges(project_id) WHERE deleted = FALSE;
-- Supports "everything planned for this person", used by the workload views
-- that look across projects.
CREATE INDEX        idx_pc_user      ON plan_charges(user_id)    WHERE deleted = FALSE;

-- -- Actual workload ---------------------------------------------
-- What was really worked, declared by the person and then approved by somebody
-- else. Same shape as plan_charges, plus the three columns of that approval.
CREATE TABLE charges_reelles (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    period        DATE         NOT NULL,   -- always the 1st day of the month
    -- The man-days really worked. Same NUMERIC(5,2) as the planned column so
    -- that the two can be compared and subtracted with no conversion and no
    -- rounding difference.
    actual_days   NUMERIC(5,2) NOT NULL,
    -- When the person declared the line. Nullable, because the row exists as
    -- soon as it is saved and the declaration is a second, separate step.
    submitted_at  TIMESTAMP,
    -- When the line was approved, and by whom. Both stay NULL until somebody
    -- holding VALIDATE_WORKLOAD approves it. A line with validated_at set to
    -- NULL is a line that has not been approved yet, which is what the
    -- workload screens filter on.
    validated_at  TIMESTAMP,
    -- The approver, and this is NOT the author: user_id above says who did the
    -- work, this column says who took responsibility for accepting it.
    -- Keeping the two apart is what makes the separation of duties real -
    -- without it, "who approved this month" would have no answer and the
    -- approval step would be a button that leaves no trace.
    -- It is also a different question from the created_by / updated_by columns
    -- that V19 adds: those say who touched the row at all.
    validated_by  BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Same reasoning as on plan_charges: no cascade anywhere, because a
    -- declared and approved month is an accounting fact that must not
    -- disappear with a project or an account.
    CONSTRAINT fk_cr_project      FOREIGN KEY (project_id)  REFERENCES projects(id),
    CONSTRAINT fk_cr_user         FOREIGN KEY (user_id)     REFERENCES users(id),
    -- Third foreign key, to users again, for the approver. Nullable by
    -- construction: it points at nothing until the line is approved. What it
    -- prevents: an approver id matching no account, which would show an empty
    -- name in the "validated by" column with no way to find out who it was.
    CONSTRAINT fk_cr_validated_by FOREIGN KEY (validated_by) REFERENCES users(id),
    -- ">= 0" here, where the planned table demands "> 0". Declaring zero days
    -- worked is a real statement: it says "I was assigned to this project this
    -- month and I did not work on it", which is exactly what the comparison
    -- with the plan has to show. Negative days, on the other hand, would
    -- silently reduce the consumed cost of the project and make the margin
    -- look better than it is.
    CONSTRAINT chk_cr_days        CHECK (actual_days >= 0 AND actual_days <= 31)
);

-- One ACTIVE declaration per project, per person, per month - the same partial
-- unique index as on the planned side, for the same reason.
-- WHAT IT PREVENTS here is more serious than on the plan: two declarations for
-- the same month would be counted twice by KpiService, so the consumed cost of
-- the project would be doubled and the margin shown to the director would be
-- wrong in the direction that hurts.
CREATE UNIQUE INDEX uk_cr_active   ON charges_reelles(project_id, user_id, period) WHERE deleted = FALSE;
-- Supports "everything declared on project 7", the query behind the consumed
-- cost, the margin and the EVM actual cost.
CREATE INDEX        idx_cr_project ON charges_reelles(project_id) WHERE deleted = FALSE;
-- Supports "everything this person declared", used by the personal workload
-- screen of the developer.
CREATE INDEX        idx_cr_user    ON charges_reelles(user_id)    WHERE deleted = FALSE;

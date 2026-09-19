-- =============================================================
-- V5 : Projects
-- =============================================================
--
-- WHAT THIS FILE IS
--   Creates the "projects" table, the central table of the whole application.
--   Every other module hangs from it: teams (V6), workload (V7), KPI (V8),
--   billing (V9), missions (V10), governance (V11), the internal quote (V23)
--   and the agile board (V27) all carry a project_id.
--
-- WHERE IT SITS IN THE FLOW
--   Runs after: V1, because the two people in charge of a project are rows of
--     the users table and a foreign key cannot point at a table that does not
--     exist yet.
--   Mapped by: Project.java (com.pms.project.entity), with the life cycle of
--     the status column mirrored by the enum ProjectStatus.
--   Read and written by: ProjectService, and read by roughly fifteen other
--     services which all start with a loadProject() helper so that an unknown
--     or removed project answers 404 before anything else happens.
--   Extended later by: V14 (fiche d'identification - client, engagement type,
--     business model), V16 (archived flag), V17 (the date rule below is
--     corrected), V18 (the unique rule on code becomes a partial index), V19
--     (created_by / updated_by).
--
-- WHY IT EXISTS
--   Delete this table and nothing else in the application has a subject. Every
--   module would lose the foreign key it hangs from, and the boot would stop
--   immediately because Project.java would have no table to validate against.
--
-- HOW THIS TABLE TAKES PART IN SECURITY (ADR-021) - important for the jury
--   Holding the permission EDIT_PROJECT is NOT the same as being allowed to
--   edit project 9. For every URL of the shape /api/projects/{id}/**,
--   ProjectScopeInterceptor checks the permission AND the perimeter. The
--   perimeter is computed from real relationships, not from a role name:
--   ProjectRepository.findAccessibleProjectIdsByEmail keeps the projects where
--   the person is the chef de projet (the column chef_projet_id below) or an
--   active member of the team (table team_assignments, V6).
--   Note carefully what is NOT in that query: director_id. A director does not
--   see the portfolio because of this column but because of the capability
--   VIEW_ALL_PROJECTS added by V13, which lifts the perimeter filter
--   altogether. This is the difference between an attribute of the row and a
--   capability of the user.
--   What this closes: without the perimeter check, typing /api/projects/9 in
--   the address bar instead of /api/projects/4 would open a project the user
--   has nothing to do with. The attack has a name, IDOR (Insecure Direct
--   Object Reference).

CREATE TABLE projects (
    id                  BIGSERIAL       PRIMARY KEY,
    -- The short reference the company already used on paper before PMS, for
    -- example PRJ-2026-014. It is what people say out loud; the numeric id
    -- means nothing to anybody outside the database.
    code                VARCHAR(20)     NOT NULL,
    name                VARCHAR(255)    NOT NULL,
    -- TEXT and not VARCHAR(n): a description has no natural maximum length,
    -- and in PostgreSQL TEXT costs exactly the same as VARCHAR. Picking a
    -- limit would mean an ALTER TABLE on a live table the first time somebody
    -- writes a long paragraph.
    description         TEXT,
    -- Where the project stands in its life cycle. Stored as text and not as a
    -- number, so the table stays readable by a human: a row that says ON_HOLD
    -- explains itself, a row that says 2 does not - and inserting a new value
    -- in the middle of the Java enum one day would silently change the meaning
    -- of every stored number.
    -- DEFAULT 'DRAFT' matches @Builder.Default in Project.java: a project that
    -- is created without an explicit status starts as a draft rather than
    -- failing on the NOT NULL.
    status              VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    -- Both dates may be NULL: a project sheet is often opened before the
    -- planning is decided. That is also why the date rule further down has to
    -- handle NULL explicitly.
    start_date          DATE,
    end_date            DATE,
    -- The budget agreed at the start, and the budget after the amendments
    -- (avenants, V9) have been taken into account. Keeping both means the
    -- drift can still be shown: initial_budget is never rewritten.
    -- NUMERIC(15,2) and never a floating point type - see the note on money in
    -- V3. Fifteen digits leaves room for a budget in a currency with large
    -- numbers.
    initial_budget      NUMERIC(15,2),
    revised_budget      NUMERIC(15,2),
    -- The director responsible for the project, above the project manager.
    -- Nullable: the sheet can be opened before a director is named.
    director_id         BIGINT,
    -- The chef de projet (project manager) who runs it day to day. This column
    -- is one of the two things the perimeter of ADR-021 is built from - see
    -- the header block above. Nullable for the same reason as director_id.
    chef_projet_id      BIGINT,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    -- Soft delete, as everywhere. A removed project must keep its charge
    -- lines, its invoices and its KPI history readable.
    deleted             BOOLEAN         NOT NULL DEFAULT FALSE,
    -- One code, one project. Without it two projects could both be called
    -- PRJ-2026-014 and nobody reading a report could tell which one is meant.
    -- KNOWN LIMIT, corrected later: an ABSOLUTE unique constraint counts the
    -- soft-deleted rows too, so a code could never be reused after a project
    -- was removed. V18 drops it and replaces it with a partial unique index
    -- (WHERE deleted = FALSE).
    CONSTRAINT uk_projects_code     UNIQUE (code),
    -- No ON DELETE CASCADE on either foreign key, and that is the point:
    -- users are soft-deleted, so nothing ever cascades here. What these two
    -- constraints buy is a refusal to store a director_id or a chef_projet_id
    -- that matches no account - a project that shows an empty name in the
    -- "responsible" column with no way to find out who it was.
    CONSTRAINT fk_projects_director FOREIGN KEY (director_id)    REFERENCES users(id),
    CONSTRAINT fk_projects_chef     FOREIGN KEY (chef_projet_id) REFERENCES users(id),
    -- A project cannot end before it starts.
    -- The first half of the OR handles the NULL case: a project with no end
    -- date yet is accepted. Remember how a CHECK works - it refuses a row only
    -- when the condition is clearly FALSE, and any comparison with NULL gives
    -- "unknown", which is not false.
    -- CORRECTED LATER by V17: the strict ">" written here refuses a project
    -- that starts and ends on the same day, which the business rules allow
    -- (a one-day project lasts one day, the Excel model counts both bounds).
    -- V17 drops this constraint and puts back the same rule with ">=".
    CONSTRAINT chk_project_dates    CHECK (end_date IS NULL OR end_date > start_date),
    -- A budget cannot be negative. The "IS NULL OR" is needed because the
    -- column is nullable: without it the intention is the same, but writing it
    -- makes the reader sure the NULL case was thought about.
    -- What it catches: a minus sign typed by mistake, which would turn the
    -- margin of the project positive on the KPI screen and would look like
    -- good news.
    CONSTRAINT chk_initial_budget   CHECK (initial_budget IS NULL OR initial_budget >= 0),
    -- The list of the five legal statuses, kept in step with the Java enum
    -- ProjectStatus. There are two guards on purpose: ProjectService.changeStatus
    -- asks the enum whether the move is allowed (DRAFT can become ACTIVE or
    -- CANCELLED, and so on), and this CHECK refuses an impossible value
    -- whatever the road it came by - a migration, a seed file, somebody typing
    -- an UPDATE by hand.
    -- What it catches: a status spelled COMPLETE instead of COMPLETED, which
    -- would make the project disappear from every filtered list without any
    -- error being raised anywhere.
    -- The consequence to remember: adding a sixth value to the Java enum
    -- without a migration that rewrites this CHECK gives an application that
    -- compiles, starts, and fails only when a user tries to use the new
    -- status.
    CONSTRAINT chk_status           CHECK (status IN ('DRAFT','ACTIVE','ON_HOLD','COMPLETED','CANCELLED'))
);

-- The three indexes below are PARTIAL (WHERE deleted = FALSE): only the live
-- rows are indexed. The queries of this project all carry "AND deleted =
-- false", so the condition of the index matches the condition of the query and
-- PostgreSQL can use it, while the index itself stays small however many
-- projects are archived over the years.

-- Supports the filtered lists of the projects screen ("show me the active
-- ones") and the dashboard counters grouped by status.
CREATE INDEX idx_projects_status       ON projects(status) WHERE deleted = FALSE;
-- Supports the perimeter question of ADR-021, "which projects does this person
-- lead", which runs on EVERY request that touches a project. Without it that
-- check reads the whole table on each call - the single most repeated query of
-- the application.
CREATE INDEX idx_projects_chef         ON projects(chef_projet_id) WHERE deleted = FALSE;
-- Supports the symmetric question on the director side, used by the portfolio
-- screens that group the projects by the director responsible for them.
CREATE INDEX idx_projects_director     ON projects(director_id) WHERE deleted = FALSE;

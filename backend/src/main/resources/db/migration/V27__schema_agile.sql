-- =============================================================
-- V27 : Agile planning — Sprints and product backlog
-- -------------------------------------------------------------
-- Delivers the "planification agile (sprints, backlog)" requirement of the
-- assigned subject. The module is deliberately self-contained: it references
-- projects(id) and nothing else, so the workload, billing, KPI and internal
-- quote engines are unaffected.
--
-- Enum values are in English. These are new enums with no persisted rows
-- anywhere, so the migration risk documented in docs/ENGLISH_MIGRATION_AUDIT.md
-- section 5 does not apply. This follows ProjectStatus, which is already
-- English, and does not reopen the declined rename of the legacy enums.
--
-- WHAT THIS FILE IS
-- A Flyway migration (Flyway plays each .sql file of this folder once, in
-- version order, and records that it did). It creates the two tables of the
-- agile module and the two permissions that guard them.
--
-- WHERE IT SITS IN THE FLOW
--   HTTP /api/projects/{id}/sprints and /api/projects/{id}/backlog
--     -> SprintController / BacklogItemController (no security check of their own)
--     -> SprintService / BacklogItemService : carry
--        @PreAuthorize("hasAuthority('VIEW_AGILE')") to read and
--        @PreAuthorize("hasAuthority('MANAGE_AGILE')") to write — the check sits
--        on the SERVICE, never on the controller, and never tests a role name
--     -> SprintRepository / BacklogItemRepository : read and write these tables
--     -> com.pms.agile.entity.Sprint and BacklogItem : one object = one row here.
--   ADR-021 adds a second gate: ProjectScopeInterceptor sees the URL pattern
--   /api/projects/{id}/** and refuses the call when the caller has no right on
--   THAT project. Holding MANAGE_AGILE is not enough by itself — a project
--   manager could otherwise reorganise the board of a project that is none of
--   his business.
--
-- WHY IT EXISTS
-- Delete this file and the whole agile board disappears: there is nowhere to
-- store a sprint or a backlog item, and the two permissions the services ask for
-- would not exist, so every agile endpoint would answer 403.
--
-- WHY THE MODULE IS SELF-CONTAINED
-- It points at projects(id) and at nothing else. That is the reason a jury can
-- be told the agile screens cannot break a margin or an invoice: no KPI, no
-- billing milestone and no quote line reads these two tables.
-- =============================================================

-- ── Sprints ──────────────────────────────────────────────────
-- One row = one sprint of one project. A sprint is a fixed period of work the
-- team commits to, with a goal and a start and end date.
CREATE TABLE sprints (
    -- BIGSERIAL = 8-byte counter filled in by PostgreSQL. BIG rather than plain
    -- SERIAL to stay consistent with every other id of this schema, which the
    -- Java BaseEntity maps as a Long.
    id          BIGSERIAL     PRIMARY KEY,
    -- The owning project. NOT NULL: a sprint that belongs to no project could
    -- never be reached by any screen, since every agile URL starts with a
    -- project id.
    project_id  BIGINT        NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    -- The sprint goal, one or two sentences. Nullable: a sprint can be created
    -- during planning before the team has agreed on its goal.
    goal        VARCHAR(1000),
    -- DATE and not TIMESTAMP: a sprint starts on a day, not at an instant. A
    -- timestamp would drag a time zone into it, and a sprint created in Tunis
    -- could then appear to start the day before for a viewer set to UTC.
    start_date  DATE          NOT NULL,
    end_date    DATE          NOT NULL,
    -- Life cycle of the sprint, stored as text and mapped in Java by
    -- @Enumerated(EnumType.STRING) on SprintStatus.
    -- Why text and not the enum position: inserting a new value in the middle of
    -- the Java enum would silently renumber every stored row if positions were
    -- stored, and a CLOSED sprint could come back as ACTIVE.
    -- DEFAULT 'PLANNED' so a row inserted by hand, without going through the
    -- service, still lands in a valid state instead of NULL.
    status      VARCHAR(10)   NOT NULL DEFAULT 'PLANNED',
    -- The four audit columns used on every business table of this schema. They
    -- are filled by the JPA auditing of BaseEntity, so created_by holds the
    -- e-mail of the authenticated caller, not the database user.
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    -- Soft delete: removing a sprint only flips this flag. Why: backlog items
    -- point at the sprint, and a physical delete would either destroy that
    -- history or be refused by the foreign key below.
    deleted     BOOLEAN       NOT NULL DEFAULT FALSE,
    -- The database itself refuses a sprint pointing at a project id that does
    -- not exist. No ON DELETE clause on purpose: projects are never physically
    -- deleted here (they carry the same "deleted" flag), so a CASCADE would
    -- describe something that cannot happen and, if it ever did, would wipe the
    -- whole board without a trace.
    CONSTRAINT fk_sprint_project FOREIGN KEY (project_id) REFERENCES projects(id),
    -- The list of valid states is kept by the database as well as by the Java
    -- enum. Why both: a fix applied straight in psql, a data import or a future
    -- service can bypass Java. Without this CHECK a row with status = 'ACTIF'
    -- would be accepted and would then crash Hibernate with "No enum constant"
    -- the next time the board is opened.
    CONSTRAINT chk_sprint_status CHECK (status IN ('PLANNED','ACTIVE','CLOSED')),
    -- A sprint cannot end before it starts. The service checks this too, but the
    -- database is the last line: without it, a typo (2026-03-01 to 2026-02-01)
    -- would be stored and the board would draw a sprint of minus 28 days.
    CONSTRAINT chk_sprint_dates  CHECK (end_date >= start_date)
);

-- The board always asks for the sprints of ONE project, so the index is on
-- project_id. WHERE deleted = FALSE makes it a PARTIAL index: soft-deleted rows
-- are simply not in it, so it stays small even after years of closed sprints,
-- and it matches exactly the queries the repository writes (which all filter on
-- deleted = false).
CREATE INDEX idx_sprint_project ON sprints(project_id) WHERE deleted = FALSE;

-- ── Product backlog ──────────────────────────────────────────
-- One row = one item of work (a feature, a bug, a task) of one project.
-- sprint_id is nullable on purpose: a NULL means the item sits in the product
-- backlog and has not been committed to a sprint yet. Detaching an item is
-- therefore a normal state, not an error.
CREATE TABLE backlog_items (
    id            BIGSERIAL      PRIMARY KEY,
    -- The project owns the item, the sprint only borrows it. That is why
    -- project_id is NOT NULL while sprint_id is not: moving an item out of a
    -- sprint must never make it homeless.
    project_id    BIGINT         NOT NULL,
    sprint_id     BIGINT,
    title         VARCHAR(255)   NOT NULL,
    description   VARCHAR(2000),
    -- Priority, stored as text for the same reason as the sprint status above.
    -- DEFAULT 'MEDIUM' so an item created quickly during a meeting still has a
    -- usable priority instead of NULL, which the board could not sort.
    priority      VARCHAR(10)    NOT NULL DEFAULT 'MEDIUM',
    -- Estimated size in days. NUMERIC(6,2) = exact decimal, so half-days (0.50)
    -- are possible and a sum of estimates cannot drift the way binary floats do.
    -- Nullable: an item that has not been estimated yet is normal, and 0 would
    -- have meant "estimated, and it costs nothing".
    estimate_days NUMERIC(6,2),
    -- Where the item stands on the board. DEFAULT 'TODO' so a new item always
    -- appears in the first column.
    status        VARCHAR(15)    NOT NULL DEFAULT 'TODO',
    created_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    deleted       BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_backlog_project  FOREIGN KEY (project_id) REFERENCES projects(id),
    -- The foreign key on a NULLABLE column: PostgreSQL checks it only when the
    -- value is not NULL, so an item with no sprint is accepted, while an item
    -- pointing at sprint 999 is refused. Without it, closing and re-opening the
    -- board could show items attached to a sprint that never existed.
    CONSTRAINT fk_backlog_sprint   FOREIGN KEY (sprint_id)  REFERENCES sprints(id),
    -- Same argument as chk_sprint_status: the database keeps the list of valid
    -- values, so a row written outside the application cannot poison the enum
    -- mapping in Java.
    CONSTRAINT chk_backlog_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT chk_backlog_status   CHECK (status   IN ('TODO','IN_PROGRESS','DONE')),
    -- "IS NULL OR >= 0" and not simply ">= 0". Why the first half is needed: in
    -- SQL a comparison with NULL gives NULL, not TRUE, and a CHECK that returns
    -- NULL passes — but writing it this way states the intention plainly: an
    -- estimate is either unknown, or a positive number of days. A sprint holding
    -- an item of −5 days would show a capacity larger than the work in it.
    CONSTRAINT chk_backlog_estimate CHECK (estimate_days IS NULL OR estimate_days >= 0)
);

-- Two partial indexes for the two ways the board reads this table: "all the
-- items of project 12" (the product backlog view) and "all the items of sprint
-- 34" (the sprint view). Without the second one, opening a sprint would scan
-- every item of every project in the database.
CREATE INDEX idx_backlog_project ON backlog_items(project_id) WHERE deleted = FALSE;
CREATE INDEX idx_backlog_sprint  ON backlog_items(sprint_id)  WHERE deleted = FALSE;

-- ── Permissions ──────────────────────────────────────────────
-- Two capabilities, read and write, kept separate so a developer can follow the
-- board without being able to re-plan it. The French text is DATA displayed as
-- is in the admin screens, not a comment, so it stays in French.
-- The doubled apostrophe in 'd''un projet' is how SQL escapes a single quote
-- inside a single-quoted string; one quote alone would end the string early and
-- break the statement.
INSERT INTO permissions (code, module, description, created_at, updated_at, deleted) VALUES
    ('VIEW_AGILE',   'AGILE', 'Consulter le backlog produit et les sprints d''un projet.',        NOW(), NOW(), FALSE),
    ('MANAGE_AGILE', 'AGILE', 'Créer et modifier les sprints et les éléments du backlog.',        NOW(), NOW(), FALSE)
-- ON CONFLICT (code) targets the unique constraint on permissions.code
-- (uk_permissions_code, V1): an existing code is skipped instead of aborting the
-- whole migration, which would leave the grants below undone.
ON CONFLICT (code) DO NOTHING;

-- Grants follow the matrix rebuilt by V12, NOT the original V11 grants: V12
-- deleted every row and cut ADMIN back to platform administration plus TCC.
-- Granting ADMIN here would reintroduce exactly what that audit removed.
--
--   MANAGE_AGILE : CHEF_PROJET only — planning the work of a project is the
--                  project manager's responsibility.
--   VIEW_AGILE   : DIRECTEUR, CHEF_PROJET, DEVELOPPEUR — a board a developer
--                  cannot see would be pointless, and the backlog carries no
--                  financial data, so BR-050 is not engaged.

-- Reading the join: the ON condition names no column of roles, so this is not a
-- join on a foreign key — it pairs every role with that one permission row, and
-- the WHERE keeps the pair wanted. The ids are looked up rather than written as
-- numbers because BIGSERIAL gives different ids in every database.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'MANAGE_AGILE'
WHERE r.name = 'CHEF_PROJET'
-- (role_id, permission_id) is the primary key of role_permissions, so DO NOTHING
-- covers the only possible clash: the grant already being there.
ON CONFLICT DO NOTHING;

-- Same shape, but the WHERE keeps three roles, so this one INSERT writes three
-- rows in a single statement.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'VIEW_AGILE'
WHERE r.name IN ('DIRECTEUR', 'CHEF_PROJET', 'DEVELOPPEUR')
ON CONFLICT DO NOTHING;

-- Bump token_version so active sessions pick up the new permissions without a
-- re-login (ADR-017).
-- How it works: each access token carries a "tokenVersion" claim (a claim is one
-- named value written inside the token) and the ready-made authorities are
-- cached under the key "<e-mail>:<tokenVersion>". Adding 1 here makes every
-- token signed earlier stale, so the next request rebuilds the authority list
-- from the database and sees VIEW_AGILE / MANAGE_AGILE.
-- Without this line, everybody already logged in would get 403 on the new agile
-- screens until their token expired, which looks exactly like a broken feature.
-- ADMIN is absent from the list on purpose: V12 took the project modules away
-- from that role, so it receives neither permission and has no session to
-- refresh here.
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));

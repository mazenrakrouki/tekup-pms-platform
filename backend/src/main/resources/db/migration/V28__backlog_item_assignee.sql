-- =============================================================
-- V28 : Backlog item assignee
-- -------------------------------------------------------------
-- A sprint backlog says what the team committed to; it says nothing about who
-- is doing it. This adds that link, so a developer can see their own work on
-- the board and a project manager can see how the sprint is distributed.
--
-- Nullable on purpose: an item can be committed to a sprint before anyone picks
-- it up, and an item sitting in the product backlog normally has no owner at
-- all. Unassigned is a state, not a defect.
--
-- ON DELETE is deliberately absent: users are never physically deleted in this
-- schema (BaseEntity carries a `deleted` flag), so a cascade would encode a
-- deletion that cannot happen. The foreign key exists to stop an item pointing
-- at an account that never existed.
--
-- WHAT THIS FILE IS
-- A Flyway migration (Flyway plays each .sql file of this folder once, in
-- version order, and records that it did). It adds one column, one foreign key
-- and one index to the table V27 created.
--
-- WHERE IT SITS IN THE FLOW
--   V27 created backlog_items. V28 is the last migration of the schema so far.
--   Java side: com.pms.agile.entity.BacklogItem holds the "assignee" field that
--   maps this column; BacklogItemService.resolveAssignee() checks that the user
--   id sent in the request body really belongs to the team of THIS project
--   before writing it — ProjectScopeInterceptor (ADR-021) guards the project id
--   in the URL, but it cannot guard an id hidden inside the body;
--   BacklogItemMapper then turns the assignee into assigneeId + assigneeName for
--   the board, so the JSON never carries the rest of the user row (e-mail,
--   password hash, token version).
--
-- WHY IT EXISTS
-- Delete this file and the column is gone: Hibernate would fail at startup on a
-- field with no column, and, feature-wise, the board could only say what is to
-- be done, never by whom.
--
-- WHY A NEW FILE RATHER THAN AN EDIT OF V27
-- Flyway stores a checksum of every migration it has applied; editing V27 would
-- change that checksum and Flyway would then refuse to start against any
-- database that already ran the old text.
-- =============================================================

-- One nullable column. No DEFAULT and no NOT NULL: there is no sensible "default
-- owner" for a task, and forcing one would put somebody's name on work nobody
-- agreed to take.
-- Note it is written without IF NOT EXISTS, unlike V22 and V25. That is safe
-- because Flyway plays this file exactly once per database; it only means the
-- file cannot be replayed by hand on a database that already has the column.
ALTER TABLE backlog_items
    ADD COLUMN assignee_id BIGINT;

-- The foreign key is added in a second statement rather than inside the ADD
-- COLUMN: both forms work in PostgreSQL, and splitting them makes the error
-- message point at the constraint when an old row would violate it.
-- On a nullable column PostgreSQL checks the key only when a value is present,
-- so "nobody assigned" stays legal while "assigned to user 9999" is refused.
-- Without this constraint, a card could point at an account that never existed
-- and the board would crash when it tried to display the name.
ALTER TABLE backlog_items
    ADD CONSTRAINT fk_backlog_assignee
    FOREIGN KEY (assignee_id) REFERENCES users(id);

-- The board filters by assignee when a developer opens it, so the lookup is
-- worth an index. Partial, like the other indexes of this module: deleted rows
-- are never queried.
-- Concretely: "show me my cards" becomes a direct lookup instead of a scan of
-- every backlog item of every project ever created, and the index does not grow
-- with the soft-deleted rows the table keeps forever.
CREATE INDEX idx_backlog_assignee ON backlog_items(assignee_id) WHERE deleted = FALSE;

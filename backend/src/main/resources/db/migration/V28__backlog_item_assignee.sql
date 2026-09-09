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
-- =============================================================

ALTER TABLE backlog_items
    ADD COLUMN assignee_id BIGINT;

ALTER TABLE backlog_items
    ADD CONSTRAINT fk_backlog_assignee
    FOREIGN KEY (assignee_id) REFERENCES users(id);

-- The board filters by assignee when a developer opens it, so the lookup is
-- worth an index. Partial, like the other indexes of this module: deleted rows
-- are never queried.
CREATE INDEX idx_backlog_assignee ON backlog_items(assignee_id) WHERE deleted = FALSE;

-- V28: adds backlog_items.assignee_id so a developer sees their own work on
-- the board and a PM sees how the sprint is distributed. Nullable: an item can
-- be unassigned. No ON DELETE: users are soft-deleted, never physically removed.

-- No DEFAULT/NOT NULL: there's no sensible default owner for a task.
ALTER TABLE backlog_items
    ADD COLUMN assignee_id BIGINT;

-- Checked only when a value is present, so unassigned stays legal while an
-- unknown user id is refused.
ALTER TABLE backlog_items
    ADD CONSTRAINT fk_backlog_assignee
    FOREIGN KEY (assignee_id) REFERENCES users(id);

-- Partial index (live rows only) for "show me my cards".
CREATE INDEX idx_backlog_assignee ON backlog_items(assignee_id) WHERE deleted = FALSE;

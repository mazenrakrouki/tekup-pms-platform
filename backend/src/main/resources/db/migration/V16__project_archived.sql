-- V16: adds the "archived" flag to projects. A COMPLETED project can be archived: it leaves the everyday
-- lists but stays fully readable in a separate "Archived projects" screen (ProjectService.archive/unarchive,
-- EDIT_PROJECT-gated since archiving hides rather than destroys).
--
-- Deliberately a separate flag from status and from "deleted": status (DRAFT/ACTIVE/.../COMPLETED) describes
-- the work's life, archived describes where the row is displayed, deleted (V1) means gone from the app
-- entirely. Folding archiving into status would force every report to test two things instead of one.
-- ProjectService.archive refuses a non-COMPLETED project (HTTP 400); unarchive has no such rule so a mistaken
-- archive can be undone in one click.

-- BOOLEAN NOT NULL DEFAULT FALSE: NOT NULL matters most - the reading queries test "p.archived = false", and
-- in SQL NULL compared to FALSE is "unknown" (excluded from results), so a nullable column left empty on
-- existing rows would have made every project vanish from the main list with no error.
-- No index: the project count is small (tens of rows), cheaper to scan than to maintain an extra index.
ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;

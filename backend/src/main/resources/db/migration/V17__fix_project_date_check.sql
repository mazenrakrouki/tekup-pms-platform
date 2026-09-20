-- V17: fixes chk_project_dates on projects, replacing V5's strict "end_date > start_date" with
-- "end_date >= start_date" so a one-day project (same start and end) is legal.
--
-- The company's Excel model counts BOTH ends of a project's duration (Project.getDurationDays() is
-- ChronoUnit.DAYS.between(start, end) + 1); V5's strict constraint rejected the INSERT for a normal one-day
-- case, and since nothing in Java validates the two dates, PostgreSQL is the only guard - so it has to be right.
--
-- A CHECK constraint can't be altered in place in PostgreSQL, so it's dropped and recreated under the same
-- name so error messages, docs and Project.java comments referencing it stay true.

-- Drops V5's strict rule. No IF EXISTS: Flyway always runs V5 first, so a missing constraint would mean the
-- schema isn't what this file expects, and failing loudly is the right answer.
ALTER TABLE projects DROP CONSTRAINT chk_project_dates;
-- Recreates it with ">=" (the whole point: start = end is now a legal one-day project).
-- "end_date IS NULL OR ..." states the intent explicitly for the reader; SQL doesn't strictly need it since a
-- CHECK only rejects a plainly-FALSE row and NULL comparisons are "unknown" (so it would pass anyway).
ALTER TABLE projects ADD CONSTRAINT chk_project_dates
    CHECK (end_date IS NULL OR end_date >= start_date);

-- =============================================================
-- V6 : Team assignments (members on projects)
-- =============================================================
--
-- WHAT THIS FILE IS
--   Creates "team_assignments", the table that says which person works on
--   which project, with what label, and between which dates. It is the join
--   table between users (V1) and projects (V5), with three extra columns of
--   its own.
--
-- WHERE IT SITS IN THE FLOW
--   Runs after: V1 and V5 - both foreign keys need their target table to
--     exist first.
--   Mapped by: TeamAssignment.java (com.pms.team.entity).
--   Written by: TeamAssignmentService (assign, update, remove), whose methods
--     carry @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')") on the SERVICE,
--     not on TeamController.
--   Read by: TeamAssignmentService again for the team screen, under
--     @PreAuthorize("hasAuthority('VIEW_TEAM')"), and - this is the important
--     one - by ProjectRepository.findAccessibleProjectIdsByEmail, which is the
--     query behind ADR-021.
--   Extended by: V19 (created_by / updated_by).
--
-- WHY IT EXISTS - two jobs, not one
--   1. The business job: showing who is on the project, since when, with what
--      label, and keeping the people who have left.
--   2. The SECURITY job, and this is what a jury will ask about: this table is
--      one of the two sources of the perimeter of ADR-021. A user is allowed
--      to touch project 7 when he is its chef de projet (projects.chef_projet_id)
--      OR when he has an active row here for project 7. Delete this table and
--      every developer instantly loses access to every project he works on,
--      even though his permissions have not changed at all.
--
-- role_in_team IS NOT A SECURITY ROLE - the one confusion to avoid
--   The text stored in role_in_team ("Developpeur", "Tech Lead", "QA") is a
--   free label shown on the team screen. The security role of the person is
--   users.role_id, and what he may do comes from role_permissions. Nothing in
--   the backend ever reads role_in_team to decide anything. Writing "Admin"
--   in that column grants exactly nothing.

CREATE TABLE team_assignments (
    id              BIGSERIAL   PRIMARY KEY,
    project_id      BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    -- The label of the person inside this team - see the warning above, it is
    -- text for humans, never a security role. Kept as free text rather than a
    -- constrained list because every project names its jobs differently, and a
    -- fixed list would need a migration each time a new job title appears.
    role_in_team    VARCHAR(50) NOT NULL,
    -- The day the person joins the project. NOT NULL, unlike the dates of the
    -- projects table: an assignment with no beginning could not be compared
    -- with a month of declared work, so it would be impossible to say whether
    -- the person really was on the project in March.
    start_date      DATE        NOT NULL,
    -- The day the person leaves, or NULL while they are still on the project -
    -- which is the normal case. That is exactly why the column is nullable: a
    -- conventional far-away date would be read as a real departure by anybody
    -- writing a report later.
    end_date        DATE,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    -- Soft delete. Taking somebody off a team only sets this flag, so the
    -- months they already declared keep pointing at a row that explains why
    -- they were allowed to declare them.
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    -- ON DELETE CASCADE on the project side: if a project row were ever really
    -- deleted, its assignments would go with it instead of becoming rows that
    -- point at nothing.
    -- Be exact in front of the jury: ProjectService never issues a real
    -- DELETE, it soft-deletes, so this cascade does not fire in normal use. It
    -- is a safety net for a manual clean-up or a test database, and it also
    -- documents that an assignment has no meaning of its own once the project
    -- is gone.
    CONSTRAINT fk_ta_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    -- No cascade on the user side, deliberately, and the asymmetry is the
    -- interesting part: an assignment is part of the history of the PROJECT,
    -- not of the person. Deleting an account must never erase the fact that
    -- this work was done, because the charge lines of V7 rely on it.
    CONSTRAINT fk_ta_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    -- The assignment cannot end before it starts. ">=" and not ">": a person
    -- can be lent to a project for a single day, and that row has the same
    -- start and end date.
    -- The first half of the OR accepts a NULL end date, which is the normal
    -- state of a current team member.
    CONSTRAINT chk_ta_dates  CHECK (end_date IS NULL OR end_date >= start_date)
);

-- Partial index: only ONE ACTIVE assignment per user per project.
-- WHAT a partial unique index is: a UNIQUE rule that only looks at the rows
-- matching the WHERE, here the rows that are not soft-deleted.
-- WHY it is written this way instead of a plain UNIQUE constraint: this
-- project soft-deletes, so an absolute UNIQUE would count the removed rows and
-- a person taken off a project could never be put back on it. With the
-- condition, the old row is invisible to the rule and the person can be
-- re-assigned as many times as needed.
-- WHAT IT PREVENTS: the same person listed twice on the same project. Every
-- screen that counts the team would count them twice, and the charge lines of
-- V7 would have two rows explaining why they may declare days on that project.
-- This is exactly the pattern that V18 later applies to users.email and
-- projects.code, which were created as absolute UNIQUE constraints in V1/V5.
CREATE UNIQUE INDEX uk_ta_project_user_active ON team_assignments(project_id, user_id) WHERE deleted = FALSE;
-- Supports "the team of project 7", the query of the team screen.
CREATE INDEX idx_ta_project_id ON team_assignments(project_id) WHERE deleted = FALSE;
-- Supports "the projects of this person", which is half of the perimeter
-- calculation of ADR-021 and therefore runs on every request that touches a
-- project. Without it that check reads the whole assignment table each time.
CREATE INDEX idx_ta_user_id    ON team_assignments(user_id)    WHERE deleted = FALSE;

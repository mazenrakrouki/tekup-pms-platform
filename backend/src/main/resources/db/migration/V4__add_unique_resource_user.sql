-- V4: adds the UNIQUE constraint V3 was missing on resources.user_id, so the
-- same person can't hold two cost sheets. Matches Resource.java's
-- @OneToOne(unique = true) — the DB enforces it since ddl-auto is validate
-- (ADR-019). New file rather than an edit of V3: Flyway checksums applied files.
--
-- Known limit: this is an absolute unique constraint (unlike the partial ones
-- V18 later adds for users.email/projects.code), so a soft-deleted resource
-- still blocks a new cost sheet for that user.
ALTER TABLE resources
    ADD CONSTRAINT uk_resources_user_id UNIQUE (user_id);

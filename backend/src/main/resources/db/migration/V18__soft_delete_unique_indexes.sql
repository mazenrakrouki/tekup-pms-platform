-- V18: replaces two absolute UNIQUE constraints (users.email from V1, projects.code from V5) with partial
-- unique indexes on deleted = FALSE, so soft-deleted rows no longer permanently burn an e-mail or project code.
--
-- Before this, an absolute UNIQUE counted dead rows too: a returning employee couldn't get his old e-mail back,
-- and a project code deleted after a typo was lost forever, both surfacing as a raw duplicate-key 409.
-- Java already checks this first (UserCrudService/ProjectService call existsBy...AndDeletedFalse for a clean
-- 400), but the index is the real guard against a race between two concurrent requests.
--
-- A partial index is enforced by PostgreSQL itself on every write, not only paths that remember to call the
-- Java check. Dropping a UNIQUE constraint frees its backing index name, which is why the replacement index
-- below can reuse the exact same name (uk_users_email, uk_projects_code) - kept identical since both appear in
-- error logs and in comments on User.java/Project.java/UserRepository.java.
--
-- Not every UNIQUE rule was relaxed: uk_resources_user_id (V4) and uk_parameters_key (V3) stay absolute - only
-- the two values a human reuses in real life (e-mail, project code) were changed here.

-- ── users.email ───────────────────────────────────────────────
-- Drops the absolute rule of V1 (CONSTRAINT uk_users_email UNIQUE (email)).
ALTER TABLE users DROP CONSTRAINT uk_users_email;
-- Same rule, limited to live accounts: two rows may share an e-mail only if at most one has deleted = FALSE.
-- Also used to SEARCH: every login runs findActiveByEmailWithRole, filtering on email and deleted = false,
-- exactly this index's shape. V1's plain (non-unique) idx_users_email is now redundant with this one.
CREATE UNIQUE INDEX uk_users_email ON users(email) WHERE deleted = FALSE;

-- ── projects.code ─────────────────────────────────────────────
-- Drops the absolute rule of V5 (CONSTRAINT uk_projects_code UNIQUE (code)).
ALTER TABLE projects DROP CONSTRAINT uk_projects_code;
-- Same reasoning as the e-mail. Condition is "deleted = FALSE" only - says nothing about "archived" (V16), so
-- an archived project (still real, still under contract) keeps holding its code.
CREATE UNIQUE INDEX uk_projects_code ON projects(code) WHERE deleted = FALSE;

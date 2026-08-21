-- =============================================================
-- V18 : Contraintes UNIQUE compatibles avec le soft-delete
-- =============================================================
-- Les contraintes UNIQUE absolues sur users.email et projects.code
-- bloquaient la réutilisation de ces valeurs après un soft-delete.
-- Remplacées par des index uniques partiels (WHERE deleted = FALSE)
-- pour n'interdire les doublons que parmi les enregistrements actifs.

-- ── users.email ───────────────────────────────────────────────
ALTER TABLE users DROP CONSTRAINT uk_users_email;
CREATE UNIQUE INDEX uk_users_email ON users(email) WHERE deleted = FALSE;

-- ── projects.code ─────────────────────────────────────────────
ALTER TABLE projects DROP CONSTRAINT uk_projects_code;
CREATE UNIQUE INDEX uk_projects_code ON projects(code) WHERE deleted = FALSE;

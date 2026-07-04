-- =============================================================
-- V4 : Contrainte UNIQUE manquante sur resources.user_id
-- =============================================================
ALTER TABLE resources
    ADD CONSTRAINT uk_resources_user_id UNIQUE (user_id);

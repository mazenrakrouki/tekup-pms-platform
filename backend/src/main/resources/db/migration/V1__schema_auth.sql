-- =============================================================
-- V1 : Schéma d'authentification et RBAC dynamique
-- =============================================================

-- ── Rôles ─────────────────────────────────────────────────────
CREATE TABLE roles (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_roles_name UNIQUE (name)
);

-- ── Permissions ───────────────────────────────────────────────
CREATE TABLE permissions (
    id         BIGSERIAL    PRIMARY KEY,
    code       VARCHAR(100) NOT NULL,
    module     VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

-- ── Mapping Rôle ↔ Permission (RBAC dynamique — ADR-001) ──────
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role
        FOREIGN KEY (role_id)       REFERENCES roles(id)       ON DELETE CASCADE,
    CONSTRAINT fk_rp_permission
        FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
);

-- ── Utilisateurs ──────────────────────────────────────────────
CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    first_login   BOOLEAN      NOT NULL DEFAULT TRUE,
    token_version INT          NOT NULL DEFAULT 0,
    role_id       BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT fk_users_role  FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- ── Index ─────────────────────────────────────────────────────
CREATE INDEX idx_users_email      ON users(email)           WHERE deleted = FALSE;
CREATE INDEX idx_users_role_id    ON users(role_id);
CREATE INDEX idx_permissions_code ON permissions(code)      WHERE deleted = FALSE;
CREATE INDEX idx_rp_role_id       ON role_permissions(role_id);

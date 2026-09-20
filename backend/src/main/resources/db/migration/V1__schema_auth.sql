-- V1: authentication schema and dynamic RBAC - creates roles, permissions, role_permissions and users, the
-- four tables the whole security model stands on. Runs before Hibernate (ddl-auto: validate, ADR-019), so
-- this file, not Java, owns the shape of the database.
--
-- ADR-001, dynamic RBAC: the link between a role and what it may do is DATA in role_permissions, never a role
-- name tested in Java - services ask "does this user hold EDIT_PROJECT", never "is this user a DIRECTEUR".
-- No refresh-token table either: both tokens are JWTs, nothing is stored server-side, and users.token_version
-- below (ADR-017) is what invalidates them instead.

-- -- Roles ------------------------------------------------------
-- Business roles (V2 seeds ADMIN, DIRECTEUR, CHEF_PROJET, DEVELOPPEUR). One user holds exactly one role.
-- A table and not a Java enum because V25 adds a live admin screen that creates roles at runtime.
CREATE TABLE roles (
    -- BIGSERIAL (64-bit, self-filling) to match the Long id in BaseEntity; a 32-bit SERIAL would mismatch and
    -- fail Hibernate's boot-time validation. The id/created_at/updated_at/deleted pattern repeats on every
    -- table in this and later migrations; explained once, here.
    id         BIGSERIAL    PRIMARY KEY,
    -- Role name (e.g. CHEF_PROJET); a label for humans and seed files only - security never compares it (ADR-001).
    name       VARCHAR(50)  NOT NULL,
    -- Filled by JPA auditing (BaseEntity); DEFAULT NOW() covers rows inserted by migrations/seeds, which never set them.
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- Soft delete: nothing is ever hard-deleted here, so last year's figures stay explainable. The tradeoff is
    -- that a UNIQUE constraint counts removed rows too - see uk_users_email and V18.
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- One role name exists only once: V2/V12/V13/V25 all attach permissions with "WHERE r.name = ...", and a
    -- duplicate name would split those grants unpredictably across two rows.
    CONSTRAINT uk_roles_name UNIQUE (name)
);

-- -- Permissions ------------------------------------------------
-- Catalogue of elementary rights; V2 seeds them, nothing creates one at runtime - a permission only matters
-- once some @PreAuthorize in Java names it.
CREATE TABLE permissions (
    id         BIGSERIAL    PRIMARY KEY,
    -- The security key: compared verbatim (case- and space-sensitive) by @PreAuthorize("hasAuthority('X')").
    -- A mistyped code (e.g. lowercase) would silently refuse everybody with no error explaining why.
    code       VARCHAR(100) NOT NULL,
    -- Functional family (PROJET, ADMIN...), used only to group rows on the admin screen - no effect on security.
    module     VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- One code exists only once; also what makes "ON CONFLICT (code) DO NOTHING" (V13, V25) possible.
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

-- -- Role to Permission matrix (dynamic RBAC - ADR-001) ----------
-- The matrix itself: changing security means changing rows here, never Java code. Loaded EAGER via
-- Role.permissions, so one login brings back the user's whole permission set in one trip.
-- No id/created_at/deleted: a pure link with no history worth keeping (V12/V20 really DELETE rows here).
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    -- Composite PK: a role can't hold the same permission twice, and it builds the "all permissions of role 3"
    -- index used on every login.
    PRIMARY KEY (role_id, permission_id),
    -- ON DELETE CASCADE: this table has no soft-delete flag, and V25's role-admin screen really deletes a role,
    -- so its grant rows must go with it - otherwise the delete is refused, or worse, a reused role id later
    -- inherits leftover grants.
    CONSTRAINT fk_rp_role
        FOREIGN KEY (role_id)       REFERENCES roles(id)       ON DELETE CASCADE,
    -- Same reasoning: V20 really deletes two permission rows and relies on this cascade.
    CONSTRAINT fk_rp_permission
        FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
);

-- -- Users ------------------------------------------------------
-- Accounts that can sign in. This is the ACCOUNT, not the cost of the person - that lives separately in
-- resources (V3), since plenty of accounts have no billable rate.
CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    -- The login; no separate username column, one identity to remember and to keep unique.
    email         VARCHAR(255) NOT NULL,
    -- BCrypt hash, never the password itself - a deliberately slow one-way function (BCryptPasswordEncoder in
    -- SecurityConfig). 255 chars, not 60, so a future algorithm change needs no column ALTER.
    password_hash VARCHAR(255) NOT NULL,
    -- Account switch, reversible, unlike deleted: FALSE means the person can't log in, rows stay readable.
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    -- TRUE until the person sets their own password; the frontend then forces the change-password screen first.
    -- DEFAULT TRUE so an admin-set initial password can't stay in place indefinitely.
    first_login   BOOLEAN      NOT NULL DEFAULT TRUE,
    -- ADR-017: since a JWT is self-contained and can't be revoked server-side, JwtService writes this value as
    -- a token claim and JwtAuthenticationFilter refuses any request where it no longer matches. Bumping it
    -- kills every token issued for that user at once - done on logout, refresh, password change (AuthService),
    -- and in SQL by V12/V13 whenever the permission matrix changes for already-logged-in users.
    token_version INT          NOT NULL DEFAULT 0,
    -- NOT NULL: a roleless account would hold no permission and fail everywhere, which is hard to diagnose.
    -- One role per user, to keep the permission calculation and the "what can this person do" answer simple.
    role_id       BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- One e-mail, one account - it IS the login, so two rows sharing one would make sign-in ambiguous.
    -- KNOWN LIMIT, fixed later: this absolute UNIQUE counts soft-deleted rows too, so a removed account's
    -- e-mail couldn't be reused. V18 replaces it with a partial unique index (WHERE deleted = FALSE).
    CONSTRAINT uk_users_email UNIQUE (email),
    -- No ON DELETE CASCADE: deleting a role that still has users must FAIL, not silently delete them.
    CONSTRAINT fk_users_role  FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- -- Indexes ----------------------------------------------------
-- "WHERE deleted = FALSE" makes each a PARTIAL index, matching this project's queries (all carry that filter)
-- and keeping the index small despite years of soft-deleted rows.

-- Login path, hit on every sign-in and token refresh (UserRepository.findActiveByEmailWithRole).
-- V18 later creates uk_users_email as a UNIQUE index on the same column/condition, making this one redundant
-- from that point on.
CREATE INDEX idx_users_email      ON users(email)           WHERE deleted = FALSE;
-- Supports the users -> roles join and "is anyone still holding this role?" (V25's role-admin screen). Not
-- partial: that question must count soft-deleted users too, or restoring one could break under a deleted role.
CREATE INDEX idx_users_role_id    ON users(role_id);
-- Supports PermissionRepository.findByCode, used by seed files and the role-admin screen.
CREATE INDEX idx_permissions_code ON permissions(code)      WHERE deleted = FALSE;
-- Supports "all permission ids of role 3" (the EAGER Role.permissions collection, loaded on every login).
-- The PK (role_id, permission_id) already supports this search; this smaller index just scans fewer pages.
CREATE INDEX idx_rp_role_id       ON role_permissions(role_id);

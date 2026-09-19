-- =============================================================
-- V1 : Authentication schema and dynamic RBAC
-- =============================================================
--
-- WHAT THIS FILE IS
--   The first migration of the project. It creates the four tables the whole
--   security of the application stands on: roles, permissions,
--   role_permissions and users.
--   Flyway is the tool that runs the .sql files of db/migration in order
--   (V1, V2, V3...) the first time it meets a database, writes down what it
--   ran in a table called flyway_schema_history, and never runs the same file
--   twice.
--
-- WHERE IT SITS IN THE FLOW
--   Runs: at application start-up, before Hibernate looks at anything. The
--     application is configured with ddl-auto: validate (ADR-019), which means
--     Hibernate never creates and never changes a table. It only checks that
--     what this file built matches the entity classes. So this file, and not
--     Java, owns the shape of the database.
--   Runs before: V2__seed_rbac.sql, which fills the four roles, the permission
--     catalogue and the role-to-permission matrix created here. Later,
--     V12 rebuilds that matrix, V13 adds VIEW_ALL_PROJECTS, V18 changes the
--     unique rule on users.email, V19 adds created_by / updated_by, and V25
--     adds a description column to roles and to permissions plus an is_system
--     flag on roles.
--   Is read at runtime by: Role.java, Permission.java and User.java (package
--     com.pms.user.entity), through RoleRepository, PermissionRepository and
--     UserRepository. At every login UserDetailsServiceImpl walks
--     user -> role -> permissions and turns each permission code into a Spring
--     Security authority. Those authority strings are exactly what
--     @PreAuthorize("hasAuthority('X')") tests on the SERVICE methods.
--
-- WHY IT EXISTS
--   Delete this file and there is no account to log in with and no authority
--   to grant, so every @PreAuthorize in the backend would refuse every call.
--   The application would not even reach that point: with ddl-auto: validate,
--   Hibernate stops the boot as soon as an entity has no table.
--
-- THE ONE DESIGN DECISION TO REMEMBER (ADR-001, dynamic RBAC)
--   RBAC means Role Based Access Control. Here the link between a role and
--   what it may do is DATA, kept in role_permissions, not Java code. Nothing
--   in the backend ever asks "is this user a DIRECTEUR". It asks "does this
--   user hold EDIT_PROJECT".
--   What this buys, concretely: giving project managers the right to assign
--   developers is one INSERT in role_permissions. Written as a test on the
--   role name in Java instead, the same change would need a new build and a
--   new deployment of the backend.
--
-- WHAT IS NOT HERE, AND WHY (refresh tokens)
--   There is no table for tokens. The access token and the refresh token are
--   both JWTs (JSON Web Tokens: signed text that the browser sends back on
--   each call); the refresh one travels in an HttpOnly cookie, which
--   JavaScript cannot read. Nothing is stored server side, so nothing can be
--   deleted to log somebody out. That is exactly the job of the
--   users.token_version column below (ADR-017).

-- -- Roles ------------------------------------------------------
-- The business roles of the company. V2 inserts the four the project ships
-- with: ADMIN, DIRECTEUR, CHEF_PROJET, DEVELOPPEUR. One user holds exactly
-- one role (see users.role_id below).
-- Why a table and not a Java enum: an enum would need a new release every time
-- the company invents a role, and V25 adds an administration screen that
-- creates roles while the application is running. Mapped by Role.java.
CREATE TABLE roles (
    -- BIGSERIAL: PostgreSQL creates a counter (a sequence) behind the column
    -- and fills it itself, so no INSERT in this project ever has to supply an
    -- id. BIG- means the value is a 64-bit BIGINT, which matches the Long of
    -- the Java @Id in BaseEntity. With a plain SERIAL (32-bit) the mapping
    -- would be Integer on one side and Long on the other, and the boot-time
    -- validation would stop the application.
    -- The same three lines - id, created_at/updated_at, deleted - repeat in
    -- every table of this file and of the migrations after it; they are
    -- explained once, here.
    id         BIGSERIAL    PRIMARY KEY,
    -- The role name, for example CHEF_PROJET. It is a label for humans and for
    -- the seed files: the security checks never compare it (ADR-001).
    name       VARCHAR(50)  NOT NULL,
    -- created_at / updated_at are filled by Spring Data JPA auditing through
    -- BaseEntity. DEFAULT NOW() is there for the rows that Java never touches,
    -- which is most of this schema: every row inserted by a migration or by a
    -- seed script. Without the default, those INSERTs would have to name the
    -- two columns or fail on the NOT NULL.
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- Soft delete. Nothing in this application is removed with a real DELETE:
    -- the flag is set to TRUE and every query adds "deleted = false".
    -- Why: a project, a charge line or a cost rate from last year still has to
    -- be readable for the figures of last year. A real DELETE would either be
    -- refused by the foreign keys pointing at the row, or would take the
    -- history away with it.
    -- The price to pay is visible right below: a UNIQUE constraint counts the
    -- removed rows too. See uk_users_email and V18.
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- One role name exists only once.
    -- Why: RoleRepository.findByName("ADMIN") must have one single answer, and
    -- V2, V12, V13 and V25 all attach permissions with "WHERE r.name = ...".
    -- With two rows called ADMIN, half the grants would land on one row and
    -- half on the other, and the administrators attached to the wrong one
    -- would lose their rights with no visible cause.
    CONSTRAINT uk_roles_name UNIQUE (name)
);

-- -- Permissions ------------------------------------------------
-- The catalogue of elementary rights: one row is one thing a user may do.
-- V2 seeds them and nothing in the application creates a permission at
-- runtime, on purpose, because a permission only does something when some
-- @PreAuthorize in the Java code names it. Mapped by Permission.java.
CREATE TABLE permissions (
    id         BIGSERIAL    PRIMARY KEY,
    -- THE key of the security system: the exact text compared by
    -- @PreAuthorize("hasAuthority('MANAGE_DI')") on a service method.
    -- The comparison is plain string equality, so it is case-sensitive and
    -- space-sensitive: a row seeded as manage_di, or with a space at the end,
    -- would never match. The check would simply refuse everybody for ever, and
    -- no error message would say why.
    code       VARCHAR(100) NOT NULL,
    -- Functional family, for example PROJET, ADMIN or CHARGE. It has no effect
    -- at all on security: it only groups the rows on the administration
    -- screen. Without it that page would be one flat list of about twenty
    -- codes and nobody could find anything in it.
    module     VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- One code exists only once.
    -- Why: two rows both called VIEW_KPI would show the right twice on the
    -- administration screen, and ticking one of the two would store a
    -- permission id that the other screens do not display.
    -- It is also what makes the "ON CONFLICT (code) DO NOTHING" of V13 and V25
    -- possible: PostgreSQL needs a unique index on code to know what a
    -- conflict is.
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

-- -- Role to Permission matrix (dynamic RBAC - ADR-001) ----------
-- The join table that says which role holds which permission. This is the
-- matrix itself: changing security in this application means changing rows
-- here, never Java code.
-- Mapped by the collection Role.permissions, which is loaded EAGER, so one
-- login brings back the whole permission set of the user in the same trip.
-- Note what this table does NOT have: no id, no created_at, no deleted flag.
-- It is a pure link between two rows, and a grant that is withdrawn has no
-- history worth keeping - V12 and V20 really do "DELETE FROM role_permissions".
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    -- The primary key is the pair of columns, not a new id.
    -- WHAT it does: the same role cannot hold the same permission twice.
    -- WHY: without it, running a seed twice would double every grant. Nothing
    -- would look broken - the user would still have the right - but the
    -- administration screen would show each permission twice, and a "remove
    -- this grant" that deletes one row would leave the right in place.
    -- It also builds the index that answers "all the permissions of role 3",
    -- which is the query run at every single login.
    PRIMARY KEY (role_id, permission_id),
    -- ON DELETE CASCADE: when a role row is really deleted, its grant rows go
    -- with it automatically.
    -- WHY a real cascade here, when the rest of the application soft-deletes:
    -- this table has no deleted flag, and V25 adds a role administration
    -- screen that really deletes a role. Without the cascade that delete would
    -- be refused by the database, because the grants still point at the role,
    -- and the administrator would only see a foreign-key error with no
    -- explanation. The situation the cascade avoids is worse still: leftover
    -- rows pointing at a role id that the sequence later gives to a brand new
    -- role, which would silently hand that new role the old one rights.
    CONSTRAINT fk_rp_role
        FOREIGN KEY (role_id)       REFERENCES roles(id)       ON DELETE CASCADE,
    -- Same reasoning on the permission side: V20 really deletes two permission
    -- rows, and it can only do so because their grants disappear with them.
    CONSTRAINT fk_rp_permission
        FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
);

-- -- Users ------------------------------------------------------
-- The accounts that can sign in. Mapped by User.java.
-- An important point for the jury: this table is the ACCOUNT, not the cost of
-- the person. What a working day of that person costs the company lives in a
-- separate table, resources (V3), because plenty of accounts have no billable
-- rate at all.
CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    -- The e-mail address is the login. There is no separate user-name column,
    -- so there is one single identity to remember and one single value to keep
    -- unique.
    email         VARCHAR(255) NOT NULL,
    -- The BCrypt hash of the password - never the password itself. BCrypt is a
    -- one-way function: it turns the password into a fixed-length string that
    -- cannot be turned back into the password, and it is deliberately slow, so
    -- trying millions of guesses costs real time. The bean that computes it is
    -- BCryptPasswordEncoder, built in SecurityConfig.
    -- Why 255 and not 60: a BCrypt hash is exactly 60 characters today, but the
    -- column would have to be altered the day the project moves to another
    -- algorithm, and an ALTER on a live users table is not free.
    -- What goes wrong without hashing: anybody who gets one copy of the
    -- database, a backup file included, can sign in as every user immediately.
    password_hash VARCHAR(255) NOT NULL,
    -- Account switch: FALSE means the person may not log in any more, but
    -- their rows stay perfectly readable.
    -- Why it is NOT the same thing as deleted: "active = false" is a decision
    -- that can be taken back (somebody on long leave), "deleted = true" means
    -- the account should no longer be offered anywhere. Both keep the history.
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    -- TRUE as long as the person has never chosen their own password. The
    -- backend answers the login with this flag and the frontend sends the user
    -- to the change-password screen before anything else.
    -- Why DEFAULT TRUE: an account created by an administrator always starts
    -- with a password the administrator knows. Without this forced step, that
    -- first password could stay in place for months, known by two people.
    first_login   BOOLEAN      NOT NULL DEFAULT TRUE,
    -- ADR-017, and this small column is the answer to "how do you log somebody
    -- out when the token is not stored anywhere?".
    -- A JWT is self-contained: the server signs it and keeps no copy, so it
    -- cannot be deleted. Instead JwtService writes the current value of this
    -- column inside the token as a claim (a claim is simply a named value
    -- carried inside the token), and JwtAuthenticationFilter compares that
    -- claim with this column on every request. If they differ the token is
    -- refused.
    -- Raising this number by one therefore kills every token already issued for
    -- that user, on every device, at once. AuthService does it on logout, on
    -- every refresh (so a stolen refresh token cannot be replayed) and on a
    -- password change; V12 and V13 do it in SQL so that a change to the
    -- permission matrix also reaches the users who are already logged in.
    -- Without it, a stolen access token would keep working until it expires and
    -- nothing could stop it.
    token_version INT          NOT NULL DEFAULT 0,
    -- The role of the user. NOT NULL: an account with no role would hold no
    -- permission at all, so it could log in and then be refused everywhere,
    -- which looks like a bug and is very hard to diagnose.
    -- One role per user, on purpose: several roles per user would make the
    -- permission calculation bigger and, above all, would make the answer to
    -- "what can this person do" much harder to read on screen.
    role_id       BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- One e-mail, one account.
    -- Why: the e-mail IS the login. Two rows with the same address would make
    -- "who is signing in" ambiguous, and the password checked would be the one
    -- of whichever row the database returned first.
    -- KNOWN LIMIT, corrected later: this is an ABSOLUTE unique constraint, so
    -- it counts the soft-deleted rows too. Removing an account and creating it
    -- again with the same address was refused. V18 drops this constraint and
    -- replaces it with a partial unique index (WHERE deleted = FALSE).
    CONSTRAINT uk_users_email UNIQUE (email),
    -- No ON DELETE CASCADE here, and that is deliberate: deleting a role that
    -- still has users must FAIL. With a cascade, removing a role on the
    -- administration screen would quietly delete the people who hold it.
    CONSTRAINT fk_users_role  FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- -- Indexes ----------------------------------------------------
-- An index is a sorted copy of one or more columns that lets PostgreSQL find
-- rows without reading the whole table. It costs a little on every write, so
-- each one below has to earn its place.
-- "WHERE deleted = FALSE" makes it a PARTIAL index: only the live rows are
-- indexed. Two gains: the index stays small even after years of soft-deleted
-- rows, and it matches exactly the queries of this project, which all carry
-- "AND deleted = false".

-- Login path: UserRepository.findActiveByEmailWithRole runs on every sign-in
-- and on every token refresh. Without an index PostgreSQL reads the whole
-- users table each time, which stays invisible with demo data and becomes the
-- slowest part of the login once the table is real.
-- To be honest in front of the jury: V18 later creates uk_users_email as a
-- UNIQUE index on exactly the same column with exactly the same condition.
-- From that point on the two indexes do the same job and this one is
-- redundant.
CREATE INDEX idx_users_email      ON users(email)           WHERE deleted = FALSE;
-- Supports the join users -> roles, and answers "is anybody still holding this
-- role?" for the role administration screen (V25), which must refuse to delete
-- a role that is still in use. Not partial on purpose: that question has to
-- count the soft-deleted users too, otherwise deleting the role would break
-- their rows the day an administrator restores one of them.
CREATE INDEX idx_users_role_id    ON users(role_id);
-- Supports PermissionRepository.findByCode, used by the seed files and by the
-- role administration screen when it attaches a permission by its code.
CREATE INDEX idx_permissions_code ON permissions(code)      WHERE deleted = FALSE;
-- Supports "give me all the permission ids of role 3", the query behind the
-- EAGER collection Role.permissions, which runs at every login.
-- Being exact about it: the primary key (role_id, permission_id) already
-- builds an index that starts with role_id, and PostgreSQL can use it for a
-- search on role_id alone. This index is smaller, so scanning it reads fewer
-- pages, but it is not what makes the lookup possible.
CREATE INDEX idx_rp_role_id       ON role_permissions(role_id);

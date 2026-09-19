-- =============================================================
-- V2 : Initial data - roles, permissions, default administrator
-- =============================================================
--
-- WHAT THIS FILE IS
--   The seed of the security system. V1 created the empty tables; this file
--   puts the first rows in them: the four business roles, the catalogue of
--   permission codes, and the matrix that says which role holds which code.
--
-- WHERE IT SITS IN THE FLOW
--   Runs: right after V1__schema_auth.sql, once, at the first start-up against
--     a fresh database. Flyway then writes it in flyway_schema_history and
--     never runs it again, which is why the file uses plain INSERT statements
--     with no "does it already exist?" test. Later migrations that may run on
--     an already-seeded database do add ON CONFLICT DO NOTHING (see V13, V25).
--   Feeds: Role.permissions, loaded EAGER at every login, which
--     UserDetailsServiceImpl turns into the Spring Security authorities tested
--     by @PreAuthorize("hasAuthority('...')") on the SERVICE methods.
--   Is corrected later by: V9 (grants the two billing codes below to the roles
--     that need them), V10 and V11 (which add the mission and governance
--     codes), V12 (the matrix below is DELETED and rebuilt
--     to match docs/AUTHORIZATION_MATRIX.md), V13 (VIEW_ALL_PROJECTS), V20
--     (MANAGE_ROLES and VIEW_AUDIT_LOG removed because nothing enforced them),
--     V25 (MANAGE_ROLES restored, descriptions added).
--   READ THIS BEFORE ANSWERING A JURY QUESTION: what this file grants is NOT
--     the matrix the running application uses. It is the starting point. The
--     matrix in force today is the one V12 rebuilt, plus V13, V20 and V25.
--
-- WHY IT EXISTS
--   Without it the tables of V1 stay empty. The first user could not be given
--   a role, every permission set would be empty, and every @PreAuthorize in
--   the backend would answer 403 - the application would start perfectly and
--   do nothing at all.
--
-- WHY THE MATRIX IS DATA AND NOT JAVA (ADR-001)
--   Every grant below is a row. Changing who may do what is an INSERT or a
--   DELETE here, with no rebuild of the backend. That is the whole point of
--   the dynamic RBAC of this project, and the reason why no Java class ever
--   compares a role name.

-- -- Roles -------------------------------------------------------
-- The four business roles of the company, in order of scope. V25 later marks
-- these four with is_system = TRUE so the administration screen cannot rename
-- or delete them.
-- The names stay in French because they are the words the company uses on
-- paper; they are labels, never compared by the security code (ADR-001).
-- Every INSERT of this file writes created_at and updated_at by hand: these
-- rows are born in SQL, so the JPA auditing that normally fills them is not
-- involved. It works because V1 declared the same values as DEFAULT NOW().
INSERT INTO roles (name, created_at, updated_at, deleted) VALUES
    ('ADMIN',        NOW(), NOW(), FALSE),
    ('DIRECTEUR',    NOW(), NOW(), FALSE),
    ('CHEF_PROJET',  NOW(), NOW(), FALSE),
    ('DEVELOPPEUR',  NOW(), NOW(), FALSE);

-- -- Permissions -------------------------------------------------
-- The catalogue of rights. Each code here is the exact string that some
-- @PreAuthorize in the Java code compares - so a typo in this list does not
-- fail anywhere: it simply creates a right that nothing will ever match, and
-- the screen it was supposed to open stays closed with no error.
-- The second column groups the codes by module for the administration screen.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
    -- Platform administration.
    -- MANAGE_ROLES and VIEW_AUDIT_LOG are seeded here but nothing enforced
    -- them at the time: V20 deletes both for that reason, and V25 brings
    -- MANAGE_ROLES back once the role administration endpoints exist.
    ('MANAGE_USERS',        'ADMIN',     NOW(), NOW(), FALSE),
    ('MANAGE_ROLES',        'ADMIN',     NOW(), NOW(), FALSE),
    ('VIEW_AUDIT_LOG',      'ADMIN',     NOW(), NOW(), FALSE),
    -- Projects.
    -- Careful, and this is ADR-021: holding VIEW_PROJECT or EDIT_PROJECT does
    -- NOT mean "on every project". For any URL that looks like
    -- /api/projects/{id}/**, ProjectScopeInterceptor also checks that this
    -- particular project is inside the caller perimeter. Permission alone is
    -- never enough. The capability that lifts the perimeter filter,
    -- VIEW_ALL_PROJECTS, does not exist yet at this point - V13 adds it.
    ('VIEW_PROJECT',        'PROJET',    NOW(), NOW(), FALSE),
    ('CREATE_PROJECT',      'PROJET',    NOW(), NOW(), FALSE),
    ('EDIT_PROJECT',        'PROJET',    NOW(), NOW(), FALSE),
    ('DELETE_PROJECT',      'PROJET',    NOW(), NOW(), FALSE),
    ('ASSIGN_CHEF_PROJET',  'PROJET',    NOW(), NOW(), FALSE),
    -- Teams: who may put people on a project, and who may look at the team.
    ('ASSIGN_DEVELOPER',    'EQUIPE',    NOW(), NOW(), FALSE),
    ('VIEW_TEAM',           'EQUIPE',    NOW(), NOW(), FALSE),
    -- Workload. Three separate codes on purpose: declaring your own days
    -- (SUBMIT), approving somebody else days (VALIDATE) and reading the
    -- figures (VIEW) are three different levels of trust, and the developer
    -- gets only the first and the third.
    -- Note for the reader: SUBMIT and VIEW are the two the developer keeps in
    -- the matrix below and after V12 as well.
    ('SUBMIT_WORKLOAD',     'CHARGE',    NOW(), NOW(), FALSE),
    ('VALIDATE_WORKLOAD',   'CHARGE',    NOW(), NOW(), FALSE),
    ('VIEW_WORKLOAD',       'CHARGE',    NOW(), NOW(), FALSE),
    -- Billing: money that comes in. Read and write are split so that a role
    -- can watch the invoicing without being able to change a milestone.
    -- Small detail worth knowing: V9 tries to insert these same two codes
    -- again with the module BILLING, but it carries ON CONFLICT (code) DO
    -- NOTHING, so the rows below win and the module stays FACTURATION. V9 only
    -- adds the grants. The effect is cosmetic - it decides the heading these
    -- two rights appear under on the administration screen.
    ('VIEW_BILLING',        'FACTURATION', NOW(), NOW(), FALSE),
    ('MANAGE_BILLING',      'FACTURATION', NOW(), NOW(), FALSE),
    -- Indicators (KPI, and later the EVM figures of V22).
    ('VIEW_KPI',            'KPI',       NOW(), NOW(), FALSE),
    -- Resources: the daily rates and the TCC coefficients created by V3. This
    -- is cost data, which is why it has its own pair of codes rather than
    -- riding on VIEW_TEAM.
    ('MANAGE_RESOURCES',    'RESSOURCE', NOW(), NOW(), FALSE),
    ('VIEW_RESOURCES',      'RESSOURCE', NOW(), NOW(), FALSE);

-- -- Role to Permission mapping ----------------------------------
-- Each block below inserts pairs (role id, permission id) into the join table
-- created by V1. They are written as INSERT ... SELECT and not as a list of
-- literal numbers on purpose: the ids come from BIGSERIAL sequences, so they
-- are not known when the file is written. Looking them up by name and by code
-- makes the file work on any database, whatever numbers the sequences gave.

-- ADMIN : everything.
-- "FROM roles r, permissions p" with a comma is a CROSS JOIN: every role row
-- is paired with every permission row, and the WHERE then keeps only the pairs
-- whose role is ADMIN. Result: one row per permission, all of them, without
-- having to list the codes.
-- Why it is written this way: the list above would have to be repeated here
-- and kept in step for ever. What it costs: any permission that exists at this
-- moment is granted, including MANAGE_ROLES and VIEW_AUDIT_LOG which nothing
-- enforces. V12 judges this too wide and cuts ADMIN down to five codes -
-- platform administration and the TCC catalogue only.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'ADMIN';

-- DIRECTEUR : portfolio governance.
-- The three blocks below use the same shape: "JOIN permissions p ON p.code IN
-- (...)". Read it carefully, because it surprises people: the ON condition
-- names only the permissions table, so there is no real join condition
-- between roles and permissions. It is a cross join filtered by the list of
-- codes, exactly like the ADMIN block above but restricted. That is the
-- intention - pair the single role row with each named permission row.
-- What would go wrong with a literal list of ids instead: the numbers depend
-- on the order of the INSERT above, and one extra line in that list would
-- shift every id and silently grant the wrong rights.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','CREATE_PROJECT','EDIT_PROJECT','ASSIGN_CHEF_PROJET',
    'VIEW_TEAM','VIEW_BILLING','MANAGE_BILLING','VIEW_KPI',
    'VIEW_WORKLOAD','VIEW_RESOURCES','MANAGE_RESOURCES'
) WHERE r.name = 'DIRECTEUR';

-- CHEF_PROJET : day-to-day running of the projects he leads.
-- He gets EDIT_PROJECT but not CREATE_PROJECT or DELETE_PROJECT: he runs
-- projects, he does not decide that they exist. And EDIT_PROJECT only bites on
-- the projects inside his perimeter, because ProjectScopeInterceptor checks
-- the scope as well (ADR-021) - his perimeter is derived from the relations
-- "chef de projet of the project" and "member of the team", never from his
-- role name.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','EDIT_PROJECT',
    'ASSIGN_DEVELOPER','VIEW_TEAM',
    'VALIDATE_WORKLOAD','VIEW_WORKLOAD',
    'VIEW_BILLING','VIEW_KPI','VIEW_RESOURCES'
) WHERE r.name = 'CHEF_PROJET';

-- DEVELOPPEUR : his own work only.
-- SUBMIT_WORKLOAD is here and VALIDATE_WORKLOAD is not: a developer declares
-- his days, somebody else approves them. Without that split, anybody could
-- approve his own declared days and the validation step would mean nothing.
-- He holds no billing code at all, here and after V12: a developer must see no
-- financial figure of the project (rule BR-050).
-- One grant below does change later: VIEW_KPI is given here and V12 takes it
-- away, replacing it with VIEW_MISSION. The reason is the same rule - the KPI
-- screen shows costs and margins.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PROJECT','VIEW_TEAM',
    'SUBMIT_WORKLOAD','VIEW_WORKLOAD',
    'VIEW_KPI'
) WHERE r.name = 'DEVELOPPEUR';

-- No user row is created here, and that is deliberate. The accounts are built
-- at start-up by DataInitializer (com.pms.shared.config), which hashes the
-- password with BCrypt before saving it - a one-way function, so the database
-- never holds a readable password. Writing an account in SQL would mean
-- writing a hash by hand in a file kept in the source repository.
-- DataInitializer can be switched off in a real deployment with the setting
-- pms.demo.seed-users=false.

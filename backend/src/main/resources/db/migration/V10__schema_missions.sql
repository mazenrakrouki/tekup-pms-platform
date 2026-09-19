-- =============================================================
-- V10 : Business trips and their cost lines
--       (original French title: "Missions professionnelles + composantes de cout")
-- =============================================================
-- WHAT THIS FILE IS
--   One Flyway migration. It creates the two tables of the mission module and
--   registers its two permissions.
--     missions            = one business trip: who went, for which project,
--                           where, and between which dates;
--     composantes_mission = the cost lines of that trip (per diem, plane
--                           ticket, stamp duty, local transport, accommodation).
--
-- WHERE IT SITS IN THE FLOW
--   Before it: V5 created "projects" and V1 created "users"; both foreign keys
--   below need them. After it: V12 rebuilds the whole role/permission matrix.
--   At runtime: MissionController (/api/projects/{projectId}/missions) ->
--   ProjectScopeInterceptor (ADR-021) -> MissionService / ComposanteService ->
--   MissionRepository / ComposanteMissionRepository. The Java entities are
--   com.pms.mission.entity.Mission and ComposanteMission.
--
-- WHY IT EXISTS
--   A trip is a real cost of a project that no timesheet records: a plane
--   ticket is not a man-day. Without these two tables, the money spent sending
--   people to a client site would simply not exist in PMS, and the project
--   margin would look better than it really is.
--
-- WHY TWO TABLES AND NOT ONE
--   A trip has ONE set of dates and MANY cost lines of different kinds. Putting
--   the costs in columns of the trip row (per_diem, ticket, transport...) would
--   mean altering the table every time the company starts reimbursing something
--   new, and would make it impossible to record two tickets on one trip.
-- =============================================================

CREATE TABLE missions (
    id              BIGSERIAL       PRIMARY KEY,
    -- The project that pays for the trip. NOT NULL: a trip with no project
    -- could not be charged to anything, and the URL of the whole module is
    -- /api/projects/{projectId}/missions, so the project is always known.
    project_id      BIGINT          NOT NULL,
    -- The person who travels. This column is what makes the "see only my own
    -- trips" rule possible: MissionRepository filters on it for a caller who
    -- holds neither MANAGE_MISSION nor VIEW_ALL_PROJECTS - in practice a
    -- developer, who may see his own trips but not those of his colleagues.
    user_id         BIGINT          NOT NULL,
    -- Purpose of the trip, in one sentence. 500 characters because it is often
    -- a full explanation ("kick-off workshop with the ministry, 3 days on site").
    objet           VARCHAR(500)    NOT NULL,
    -- Destination. Nullable: a trip can be recorded before the place is fixed.
    lieu            VARCHAR(255),
    -- Start and end of the trip. Both NOT NULL because the per diem is counted
    -- per day, so a trip without dates cannot be costed at all.
    date_debut      DATE            NOT NULL,
    date_fin        DATE            NOT NULL,
    -- The three columns every PMS table carries (see V8 for the detail).
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Foreign keys: the trip must point at a project and at a user that really
    -- exist. Without them, deleting a user in raw SQL would leave trips whose
    -- traveller is nobody, and the mission list would crash while reading the
    -- traveller's name.
    CONSTRAINT fk_mission_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_mission_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    -- WHAT: the trip cannot end before it starts. ">=" and not ">": a one-day
    --       trip has the same start and end date, and that is the most common
    --       case of all.
    -- WHY : the number of days of the trip is computed from these two dates.
    -- WITHOUT IT: a typo such as 2025-03-10 to 2025-03-01 would give a negative
    --       number of days, and the per diem computed from it would SUBTRACT
    --       money from the cost of the project.
    CONSTRAINT chk_mission_dates  CHECK (date_fin >= date_debut)
);

-- WHAT: index for "all the live trips of project 42" - the mission tab of a
--       project.
-- WHY : same partial-index reasoning as V8/V9; only the rows with
--       deleted = FALSE are indexed, because every query filters on them.
CREATE INDEX idx_mission_project ON missions(project_id) WHERE deleted = FALSE;
-- WHAT: index for "all the live trips of user 7".
-- WHY : this is the second entry point of the module, the "my trips" list a
--       developer sees. It needs its own index because an index on project_id
--       cannot answer a question asked on user_id.
-- WITHOUT IT: the personal list would scan every trip of the company, and it is
--       a screen opened by every user, not only by project managers.
CREATE INDEX idx_mission_user    ON missions(user_id)    WHERE deleted = FALSE;

-- Cost lines of one trip (French: "composantes de cout d'une mission")
CREATE TABLE composantes_mission (
    id                BIGSERIAL       PRIMARY KEY,
    -- The trip this cost belongs to. The link is to the trip and not to the
    -- project: a cost line always answers "what did THIS trip cost?", and going
    -- through the trip is what lets the total of a trip be summed in one query.
    mission_id        BIGINT          NOT NULL,
    -- What kind of cost this is. Text, mapped by the Java enum TypeComposante
    -- with @Enumerated(EnumType.STRING) - see the CHECK below for the list and
    -- for why the value is stored as a word and not as a number.
    type_composante   VARCHAR(30)     NOT NULL,
    montant           NUMERIC(15,2)   NOT NULL,
    -- Currency of THIS cost line, as a 3-letter ISO code. It is kept per line
    -- and not per trip because one trip really can mix currencies: the ticket
    -- bought in euros, the per diem paid in dinars.
    -- The default 'TND' is the currency of the company, so the common case needs
    -- no input at all.
    devise            VARCHAR(3)      NOT NULL DEFAULT 'TND',
    -- Free text for the detail ("Tunis-Abidjan return, Tunisair").
    description       VARCHAR(500),
    created_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted           BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Foreign key to the trip: a cost line with no trip could never be totalled.
    CONSTRAINT fk_comp_mission   FOREIGN KEY (mission_id) REFERENCES missions(id),
    -- WHAT: the kind of cost may only be one of these five words.
    --       PERDIEM   = daily allowance, BILLET = plane or train ticket,
    --       TIMBRE    = stamp duty / administrative fee,
    --       TRANSPORT = local transport, SEJOUR = accommodation.
    -- WHY : Hibernate maps the text back to the enum TypeComposante by NAME. A
    --       row holding "perdim" or "Per Diem" would make it throw "No enum
    --       constant com.pms.mission.entity.TypeComposante.perdim" the moment
    --       somebody opens the trip, with nothing on screen to explain it.
    --       Storing the name rather than the position also means that adding a
    --       sixth kind later cannot change the meaning of the rows already
    --       saved.
    -- WITHOUT IT: free text would let the same cost be spelled three different
    --       ways, and any report grouped by kind of cost would be wrong.
    CONSTRAINT chk_comp_type     CHECK (type_composante IN ('PERDIEM','BILLET','TIMBRE','TRANSPORT','SEJOUR')),
    -- WHAT: a cost line must be strictly positive.
    -- WHY : a cost line is an expense, never a refund. Removing an expense is
    --       done with the soft delete above.
    -- WITHOUT IT: a line of -400 could be entered and the total cost of the trip
    --       would fall, which would quietly improve the margin of the project.
    CONSTRAINT chk_comp_montant  CHECK (montant > 0)
);

-- WHAT: index for "all the live cost lines of trip 42".
-- WHY : this lookup runs every time a trip is opened, and again whenever the
--       total cost of a trip is needed.
CREATE INDEX idx_comp_mission ON composantes_mission(mission_id) WHERE deleted = FALSE;

-- ── Mission permissions ──────────────────────────────────────────
-- Same pattern as V9: authorization is dynamic and permission-based (ADR-001),
-- so a permission is a ROW, created here as data, and
-- @PreAuthorize("hasAuthority('MANAGE_MISSION')") on the service methods reads
-- that code. The code is never a role name.
-- Unlike the billing codes of V9, these two codes are NEW - V2 did not create
-- them - so these lines really do insert. ON CONFLICT (code) DO NOTHING still
-- guards a replay: uk_permissions_code (V1) would otherwise abort the migration.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_MISSION', 'MISSION', NOW(), NOW(), FALSE),
  ('VIEW_MISSION',   'MISSION', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- WHAT: gives the write permission to the two roles that organise trips.
-- HOW : "FROM roles r, permissions p WHERE ..." is a cross join narrowed by the
--       WHERE clause, so each matching role is paired with the matching
--       permission. The ids cannot be written by hand: they come from sequences.
-- NOTE: V12 later deletes every grant of these four roles and rebuilds the
--       matrix, so this block only describes the state between V10 and V12.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET') AND p.code = 'MANAGE_MISSION'
ON CONFLICT DO NOTHING;

-- WHAT: gives the read permission to four roles, the developer included.
-- WHY the developer is in this list although he is walled off from financial
--       data (BR-050): VIEW_MISSION on its own does not open the trips of the
--       whole company. MissionService.canSeeAllMissions() returns true only for
--       a caller holding MANAGE_MISSION or VIEW_ALL_PROJECTS, and a developer
--       holds neither - so findByProject() then calls
--       findActiveByProjectIdAndUserId() instead of findActiveByProjectId(),
--       which narrows the list to the trips whose user_id is his own (UC-21).
--       He sees his own travel, nothing else.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET', 'DIRECTEUR', 'DEVELOPPEUR') AND p.code = 'VIEW_MISSION'
ON CONFLICT DO NOTHING;

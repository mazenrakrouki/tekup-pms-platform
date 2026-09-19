-- =============================================================
-- V19 : created_by / updated_by on every entity table  (audit point H-6)
-- =============================================================
-- WHAT THIS FILE IS
--   One repeated change, applied to the nineteen tables that carry business
--   data: it adds the two columns created_by and updated_by. They hold the
--   NAME of the person who created the row and the name of the person who
--   changed it last. The two dates created_at and updated_at have existed
--   since V1; this migration adds the missing half of the answer: not only
--   WHEN a row changed, but WHO changed it.
--   H-6 is the reference of the finding in the audit document of the
--   project. Never renumber it: it is what ties this file back to that
--   document.
--
-- WHERE IT SITS IN THE FLOW - nobody writes these columns by hand
--   Service (this is where @PreAuthorize sits) -> Repository -> entity
--     -> BaseEntity, the parent class of every entity, which declares
--          @CreatedBy      @Column(name = "created_by", updatable = false)
--          @LastModifiedBy @Column(name = "updated_by")
--     -> Spring Data JPA auditing, switched on by JpaConfig with
--        @EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware")
--     -> SpringSecurityAuditorAware, which answers the question "who is
--        writing right now?". It reads the signed-in user out of the Spring
--        Security context and returns his e-mail, or the literal text
--        "system" when nobody is signed in (application start-up, the data
--        seeders, a scheduled job).
--     -> THESE COLUMNS.
--   updatable = false on created_by is what makes the first author
--   permanent: Hibernate leaves that column out of every UPDATE, so the name
--   of the creator cannot be rewritten by whoever edits the row next.
--
-- WHY IT EXISTS - what breaks without this file
--   1. The application does not start at all. BaseEntity maps the two
--      columns, and the project runs with ddl-auto = validate (Flyway owns
--      the schema, ADR-019): Hibernate compares the mapping with the real
--      tables at boot and stops on the first column it cannot find.
--   2. The audit trail is lost. "Who changed the rate on this internal quote
--      line?" is exactly the kind of question asked about the DI (Devis
--      Interne = internal quote), the most sensitive area of the
--      application, and these two columns are the only place the answer is
--      stored.
--
-- WHY VARCHAR(255) AND NOT A FOREIGN KEY TO users(id)
--   The value stored is a name, kept as plain text, not a link to a row. Two
--   reasons. First, some writes have no user at all: the seeders and the
--   start-up code write "system", which is not an account, so no foreign key
--   could ever accept it. Second, an audit trail must stay readable even if
--   the account disappears later; a foreign key would either block the
--   deletion of that user or, with a cascade, quietly erase the history it
--   was meant to keep.
--
-- WHY THE COLUMNS ARE NULLABLE
--   The rows already present when this migration ran have no known author,
--   and this file does not invent one for them: they simply stay empty.
--   Declaring NOT NULL would have forced a made-up value onto real history.
--   From now on the columns are filled on every write, because
--   SpringSecurityAuditorAware always returns something ("system" at worst).
--   Code that reads them must still handle the empty case. The data seeders
--   are the example: they only touch rows whose created_by is "system" or
--   empty, so they can never overwrite data typed by a real user.
--
-- WHY ONE "ALTER TABLE" PER TABLE, WITH THE TWO COLUMNS TOGETHER
--   PostgreSQL accepts several ADD COLUMN clauses in a single ALTER TABLE.
--   Written this way, each table is opened and locked once instead of twice.
--   IF NOT EXISTS on each clause makes the script safe to replay on a
--   database where somebody had already added one of the columns by hand: it
--   skips that clause instead of failing. A failed migration stops the whole
--   application from starting, for everybody.
--
-- WHICH TABLES ARE IN THE LIST, AND ONE THAT IS NOT
--   The nineteen tables below are all the tables that existed at this point
--   and that have a Java entity class extending BaseEntity.
--   role_permissions is deliberately absent: it is a pure link table between
--   a role and a permission, with no entity class of its own (the link is
--   mapped as a @ManyToMany between Role and Permission), so nothing in the
--   application would ever fill those two columns.
--   Tables created after this migration do not appear either, because they
--   declare the two columns inside their own CREATE TABLE: tcc_annuels
--   (V21), the internal quote lines (V23) and the agile tables (V27).
-- =============================================================

-- Accounts and dynamic RBAC (tables created by V1).
-- These three tables decide who may do what. A change here, such as a role
-- given one more permission, is the most sensitive edit in the whole
-- application, so knowing who made it matters most.
ALTER TABLE users               ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE roles               ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE permissions         ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Reference data (V3).
-- resources holds the daily rate and the TCC rate of each person, so every
-- cost and every margin of every project is computed from it; parameters
-- holds the application-wide settings. One rate changed by mistake moves the
-- figures on every screen, and these two columns name the author.
ALTER TABLE resources           ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE parameters          ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Projects (V5), the centre of the model: everything else points at a
-- project.
ALTER TABLE projects            ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Project teams (V6). These rows also build the data perimeter of a user
-- (ADR-021), so adding one widens what somebody is allowed to see.
ALTER TABLE team_assignments    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Workload: the planned days, then the days really declared (V7).
-- charges_reelles carries what a person says he actually worked. Knowing who
-- wrote a line is what separates a declaration made by the person himself
-- from one entered on his behalf.
ALTER TABLE plan_charges        ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE charges_reelles     ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- KPI snapshots (V8, extended by V22 with the EVM indicators).
ALTER TABLE snapshot_kpis       ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Billing: milestones, amendments, payments (V9).
-- Money moves here: an amendment changes the revised budget of the project,
-- and the planned milestone amounts are rebuilt from that budget.
ALTER TABLE jalons_facturation  ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE avenants            ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE paiements           ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Missions and their components (V10).
ALTER TABLE missions            ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE composantes_mission ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Governance: stakeholders, risks, deliverables, change requests (V11).
ALTER TABLE parties_prenantes   ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE risks               ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE livrables           ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE demandes_changement ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

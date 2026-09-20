-- V19: adds created_by/updated_by to the nineteen business tables (audit point H-6). created_at/updated_at
-- (V1) already say WHEN a row changed; these two columns add WHO, filled by Spring Data JPA auditing
-- (BaseEntity -> SpringSecurityAuditorAware, which returns the signed-in user's e-mail or "system").
--
-- Stored as VARCHAR(255) text, not a FK to users(id): some writes (seeders, start-up) have no account at all,
-- and an audit trail must stay readable even if the account is later removed. Nullable because existing rows
-- have no known author and none is invented for them; from now on every write fills them.
-- role_permissions is deliberately absent - it's a pure link table with no entity class of its own.
-- Tables created after this migration (V21, V23, V27) declare the two columns in their own CREATE TABLE.

-- Accounts and dynamic RBAC (tables created by V1) - who changes roles/permissions matters most.
ALTER TABLE users               ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE roles               ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE permissions         ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Reference data (V3): resources holds the rates that drive every project's cost and margin.
ALTER TABLE resources           ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE parameters          ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Projects (V5), the centre of the model.
ALTER TABLE projects            ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Project teams (V6); these rows also build a user's data perimeter (ADR-021).
ALTER TABLE team_assignments    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Workload: planned then declared days (V7).
ALTER TABLE plan_charges        ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE charges_reelles     ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- KPI snapshots (V8, extended by V22).
ALTER TABLE snapshot_kpis       ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Billing: milestones, amendments, payments (V9).
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

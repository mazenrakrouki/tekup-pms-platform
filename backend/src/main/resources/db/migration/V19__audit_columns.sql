-- H-6: Add created_by / updated_by audit columns to every entity table.
-- Nullable (existing rows get NULL; AuditorAware falls back to 'system' for unauthenticated writes).

ALTER TABLE users               ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE roles               ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE permissions         ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE resources           ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE parameters          ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE projects            ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE team_assignments    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE plan_charges        ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE charges_reelles     ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE snapshot_kpis       ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE jalons_facturation  ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE avenants            ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE paiements           ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE missions            ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE composantes_mission ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE parties_prenantes   ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE risks               ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE livrables           ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE demandes_changement ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
                                ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

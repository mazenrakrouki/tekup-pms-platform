-- ============================================================
-- V26 : Company demo data — Société S2I, Tunisia, TND
-- (French original: "Données démo entreprise")
-- 50 projects · 36 users · 2022-2026
-- ============================================================
-- WHAT THIS FILE IS
-- A Flyway migration (Flyway plays each .sql file of this folder once, in
-- version order, and records that it did). Unlike its neighbours it creates NO
-- table: it fills the tables built by V1..V25 with a complete, believable
-- company, so the application can be shown, measured and defended with real
-- volumes instead of three empty screens.
--
-- WHERE IT SITS IN THE FLOW
-- It comes after every schema migration it writes into, and its sections follow
-- the dependency order of the tables: parameters, users, resources, yearly TCC
-- rates, projects, team assignments, billing milestones, payments, contract
-- amendments, workload (planned and real), KPI snapshots, then the governance
-- tables (missions, risks, deliverables, stakeholders, change requests).
-- Moving a section up would break a foreign key: a team assignment cannot be
-- written before the project and the user it points at exist.
-- Nothing in the Java code reads this file; it is only the state the running
-- application finds in its database.
--
-- WHY IT EXISTS
-- Several screens only make sense with volume: the portfolio list and its
-- pagination, the EVM review with a month-by-month history, the workload heat
-- map, the invoicing follow-up. With an empty database none of them can be
-- demonstrated, and a jury cannot judge a table that shows "no data".
--
-- WHAT IT DELIBERATELY DOES NOT CONTAIN
-- No line of Devis Interne (table lignes_di, created by V23). That is the
-- decision of 2026-07-05: the DI structure is delivered empty, and the real
-- salary and margin figures of the company are NEVER written into the
-- repository. This file stops at project-level budgets.
--
-- ON THE PASSWORDS BELOW
-- They are demo accounts for a demo database. They are written in clear text in
-- this file on purpose, so the accounts can be handed over during a
-- presentation; they are hashed by the database before being stored. This file
-- must never be applied to a production database.
-- ============================================================

-- pgcrypto is a PostgreSQL extension; it adds the crypt() and gen_salt()
-- functions used in section §1 to hash the demo passwords.
-- Why hash here rather than paste ready-made hashes: a bcrypt hash pasted by
-- hand is unreadable and impossible to check, and nobody could tell which
-- password it belongs to.
-- IF NOT EXISTS: the extension may already be installed on the database, and
-- "extension already exists" would abort the whole migration.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── §-1  Clean-up (idempotence — safe to re-run) ──────────────
-- (French original: "Nettoyage (idempotence — re-run sécurisé)")
-- Deletes ONLY the rows carrying the S2I-* prefix or belonging to the demo
-- accounts, so the file can be replayed cleanly after a partial failure.
-- Why targeted deletes and not TRUNCATE: a database can already hold real data
-- created by hand (the admin account of V2, a test project). TRUNCATE would
-- destroy it; the filters below cannot touch anything that is not demo data.
--
-- DO $$ ... $$ opens an anonymous block of PL/pgSQL, the procedural language of
-- PostgreSQL. Plain SQL has no variables, no IF and no loops; this file needs
-- all three. The $$ pair is just a way of quoting the body so the apostrophes
-- inside it need no escaping.
DO $$
-- One variable: the ids of the demo projects, held as an array so the whole
-- clean-up runs on one single SELECT instead of repeating the same sub-query in
-- fourteen DELETE statements.
DECLARE v_ids BIGINT[];
BEGIN
  -- ARRAY_AGG collects every matching id into that array.
  -- LIKE 'S2I-%' : % means "any sequence of characters", so this matches every
  -- project code starting with S2I- and nothing else.
  SELECT ARRAY_AGG(id) INTO v_ids FROM projects WHERE code LIKE 'S2I-%' AND deleted = FALSE;
  -- ARRAY_AGG over zero rows returns NULL, not an empty array. Without this
  -- guard, "= ANY(NULL)" below would match nothing but the block would still run
  -- fourteen useless statements on the very first, normal execution.
  IF v_ids IS NOT NULL THEN
    -- The order of these DELETEs is the reverse of the order of creation: a row
    -- is removed before the row it points at. Deleting projects first would be
    -- refused by every foreign key below.
    -- "= ANY(v_ids)" means "is one of the values in this array"; it is the array
    -- form of IN (...).
    DELETE FROM demandes_changement  WHERE project_id = ANY(v_ids);
    DELETE FROM parties_prenantes    WHERE project_id = ANY(v_ids);
    DELETE FROM livrables            WHERE project_id = ANY(v_ids);
    DELETE FROM risks                WHERE project_id = ANY(v_ids);
    -- Two levels down: mission components hang on missions, which hang on
    -- projects, so they are reached through a sub-query on missions.
    DELETE FROM composantes_mission  WHERE mission_id IN (SELECT id FROM missions WHERE project_id = ANY(v_ids));
    DELETE FROM missions             WHERE project_id = ANY(v_ids);
    DELETE FROM snapshot_kpis        WHERE project_id = ANY(v_ids);
    DELETE FROM charges_reelles      WHERE project_id = ANY(v_ids);
    DELETE FROM plan_charges         WHERE project_id = ANY(v_ids);
    DELETE FROM avenants             WHERE project_id = ANY(v_ids);
    -- Same two-level shape: a payment hangs on a billing milestone, which hangs
    -- on a project. Deleting the milestones first would be refused by the
    -- foreign key on paiements.jalon_id.
    DELETE FROM paiements            WHERE jalon_id IN (SELECT id FROM jalons_facturation WHERE project_id = ANY(v_ids));
    DELETE FROM jalons_facturation   WHERE project_id = ANY(v_ids);
    DELETE FROM team_assignments     WHERE project_id = ANY(v_ids);
    DELETE FROM projects             WHERE id = ANY(v_ids);
  END IF;
  -- Delete the yearly TCC rates of the S2I demo accounts.
  -- (French original: "Supprimer TCC annuels des comptes démo S2I".)
  -- These three deletes are outside the IF on purpose: the demo users and their
  -- resources exist even when no demo project does — for instance if a previous
  -- run stopped between §1 and §4.
  -- The chain is tcc_annuels -> resources -> users, so it is unwound in that
  -- order; the same filter identifies a demo account each time: either the
  -- @s2i.tn domain, or one of the three explicit local accounts.
  DELETE FROM tcc_annuels WHERE resource_id IN (
    SELECT r.id FROM resources r
    JOIN users u ON u.id = r.user_id
    WHERE u.email LIKE '%@s2i.tn'
       OR u.email IN ('momo-directeur@pms.local','momo-chef@pms.local','momo-dev@pms.local')
  );
  DELETE FROM resources WHERE user_id IN (
    SELECT id FROM users
    WHERE email LIKE '%@s2i.tn'
       OR email IN ('momo-directeur@pms.local','momo-chef@pms.local','momo-dev@pms.local')
  );
  DELETE FROM users
  WHERE email LIKE '%@s2i.tn'
     OR email IN ('momo-directeur@pms.local','momo-chef@pms.local','momo-dev@pms.local');
END $$;

-- ── §0  Parameters ────────────────────────────────────────────
-- The company being simulated is Tunisian, so the default currency of the
-- application becomes the Tunisian dinar. Every budget, cost price and KPI below
-- is expressed in TND.
-- UPDATE and not INSERT: the CURRENCY row already exists, seeded by an earlier
-- migration. Inserting it again would duplicate the parameter, and the screen
-- reading it would then pick one of the two at random.
UPDATE parameters SET param_value = 'TND', updated_at = NOW() WHERE param_key = 'CURRENCY';

-- ── §1  Users ─────────────────────────────────────────────────
-- 36 accounts spread over the four roles of the RBAC matrix. They are inserted
-- role by role so the file can be read as an organisation chart.
-- Every account is created with first_login = false: the demonstration must not
-- be interrupted by the forced password change of FirstLoginFilter.
--
-- Momo accounts (password: Momo123456) — the three accounts used to show the
-- application, one per business role, so the same screen can be opened as a
-- director, as a project manager and as a developer.
-- Reading one of these rows, left to right:
--   crypt('Momo123456', gen_salt('bf',12))
--     bcrypt hashing. bcrypt is a password hashing algorithm built to be SLOW on
--     purpose, so that trying millions of passwords costs real time. 'bf' asks
--     for bcrypt ("blowfish"), 12 is the cost factor: the work is doubled each
--     time it goes up by one. gen_salt draws a fresh random salt for every row,
--     so two accounts sharing the same password still get two different hashes
--     and cannot be spotted as identical in the table.
--     This must match what Spring Security expects: BCryptPasswordEncoder reads
--     the cost out of the stored hash, so 12 here and 12 in the application are
--     the same thing. Storing the password in clear text would let anyone who
--     reads one database dump log in as anyone.
--   (SELECT id FROM roles WHERE name='DIRECTEUR')
--     the role is looked up by name because role ids come from a BIGSERIAL
--     counter and differ from one database to the next; a literal id would
--     attach the account to whatever role happens to sit at that number.
--   NOW()-INTERVAL'2 years'
--     a creation date pushed into the past, so the account list does not show
--     36 people hired on the same afternoon.
INSERT INTO users (first_name, last_name, email, password_hash, active, first_login, role_id, created_at, updated_at, deleted) VALUES
('Mohamed', 'Ben Directeur', 'momo-directeur@pms.local', crypt('Momo123456', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DIRECTEUR'),   NOW()-INTERVAL'2 years', NOW(), false),
('Mohamed', 'Ben Chef',      'momo-chef@pms.local',      crypt('Momo123456', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'),  NOW()-INTERVAL'3 years', NOW(), false),
('Mohamed', 'Ben Dev',       'momo-dev@pms.local',       crypt('Momo123456', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'),  NOW()-INTERVAL'1 year',  NOW(), false)
-- ON CONFLICT (email) WHERE deleted = FALSE names the PARTIAL unique index
-- created by V18 (uk_users_email), which forbids duplicates only among the rows
-- that are not soft-deleted. The WHERE clause is not a filter on this statement:
-- it is how PostgreSQL is told WHICH index is meant, and leaving it out would
-- fail with "no unique or exclusion constraint matching".
-- DO UPDATE and not DO NOTHING for these three accounts only: whoever runs the
-- demonstration must be certain the password is the one written above. If the
-- account already exists with another password, this resets it. EXCLUDED is the
-- row that was about to be inserted, so EXCLUDED.password_hash is the fresh
-- hash computed on the line above.
ON CONFLICT (email) WHERE deleted = FALSE DO UPDATE SET password_hash=EXCLUDED.password_hash, role_id=EXCLUDED.role_id, first_login=false, updated_at=NOW();

-- Additional directors (password: PmsUser2025!)
-- From here on the statements end with a plain ON CONFLICT DO NOTHING: these
-- accounts only fill the lists, so an account already present is left exactly as
-- it is rather than being reset.
INSERT INTO users (first_name, last_name, email, password_hash, active, first_login, role_id, created_at, updated_at, deleted) VALUES
('Karim',   'Ben Salah',  'karim.bensalah@s2i.tn',  crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DIRECTEUR'), NOW()-INTERVAL'6 years', NOW(), false),
('Nadia',   'Trabelsi',   'nadia.trabelsi@s2i.tn',   crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DIRECTEUR'), NOW()-INTERVAL'5 years', NOW(), false)
ON CONFLICT DO NOTHING;

-- Project managers / "chefs de projet" (password: PmsUser2025!)
-- Seven of them: the portfolio of 50 projects below is shared between these
-- seven plus momo-chef, so no screen ever shows one manager owning everything.
INSERT INTO users (first_name, last_name, email, password_hash, active, first_login, role_id, created_at, updated_at, deleted) VALUES
('Sonia',  'Hammami',    'sonia.hammami@s2i.tn',    crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'5 years', NOW(), false),
('Aymen',  'Chaouachi',  'aymen.chaouachi@s2i.tn',  crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'4 years', NOW(), false),
('Riadh',  'Mabrouk',    'riadh.mabrouk@s2i.tn',    crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'5 years', NOW(), false),
('Wafa',   'Bousbia',    'wafa.bousbia@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'4 years', NOW(), false),
('Tarek',  'Ghannouchi', 'tarek.ghannouchi@s2i.tn', crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'6 years', NOW(), false),
('Inès',   'Dridi',      'ines.dridi@s2i.tn',       crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'3 years', NOW(), false),
('Mehdi',  'Karray',     'mehdi.karray@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'4 years', NOW(), false)
ON CONFLICT DO NOTHING;

-- Developers — senior (password: PmsUser2025!)
-- The developers are split into three levels of seniority only so that §2 can
-- give them believable, different daily cost prices. The application itself
-- knows nothing about seniority: all of them carry the same DEVELOPPEUR role.
INSERT INTO users (first_name, last_name, email, password_hash, active, first_login, role_id, created_at, updated_at, deleted) VALUES
('Ahmed',    'Ben Ali',      'ahmed.benali@s2i.tn',       crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'7 years', NOW(), false),
('Yassine',  'Chebbi',       'yassine.chebbi@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'6 years', NOW(), false),
('Khaled',   'Turki',        'khaled.turki@s2i.tn',       crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'8 years', NOW(), false),
('Marouane', 'Toumi',        'marouane.toumi@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'5 years', NOW(), false),
('Bilel',    'Nasri',        'bilel.nasri@s2i.tn',        crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'7 years', NOW(), false)
ON CONFLICT DO NOTHING;

-- Developers — mid-level ("confirmés") (password: PmsUser2025!)
INSERT INTO users (first_name, last_name, email, password_hash, active, first_login, role_id, created_at, updated_at, deleted) VALUES
('Salma',    'Mansouri',     'salma.mansouri@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'4 years', NOW(), false),
('Hamza',    'Bouzid',       'hamza.bouzid@s2i.tn',       crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'4 years', NOW(), false),
('Olfa',     'Ben Youssef',  'olfa.benyoussef@s2i.tn',    crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'3 years', NOW(), false),
('Wassim',   'Melki',        'wassim.melki@s2i.tn',       crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'5 years', NOW(), false),
('Asma',     'Ben Romdhane', 'asma.benromdhane@s2i.tn',   crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'4 years', NOW(), false),
('Nizar',    'Ben Ayed',     'nizar.benayed@s2i.tn',      crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'4 years', NOW(), false),
('Anis',     'Louati',       'anis.louati@s2i.tn',        crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'3 years', NOW(), false),
('Firas',    'Ben Salah',    'firas.bensalah@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'5 years', NOW(), false),
('Mourad',   'Ferchichi',    'mourad.ferchichi@s2i.tn',   crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'3 years', NOW(), false),
('Sabrine',  'Chahed',       'sabrine.chahed@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'3 years', NOW(), false)
ON CONFLICT DO NOTHING;

-- Developers — junior (password: PmsUser2025!)
INSERT INTO users (first_name, last_name, email, password_hash, active, first_login, role_id, created_at, updated_at, deleted) VALUES
('Rim',      'Khalfallah',   'rim.khalfallah@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'2 years', NOW(), false),
('Sarra',    'Ayari',        'sarra.ayari@s2i.tn',        crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'2 years', NOW(), false),
('Emna',     'Gargouri',     'emna.gargouri@s2i.tn',      crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'1 year',  NOW(), false),
('Hajer',    'Sassi',        'hajer.sassi@s2i.tn',        crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'2 years', NOW(), false),
('Chiraz',   'Sfar',         'chiraz.sfar@s2i.tn',        crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'1 year',  NOW(), false),
('Dorra',    'Hammami',      'dorra.hammami@s2i.tn',      crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'2 years', NOW(), false),
('Leila',    'Ben Fredj',    'leila.benfredj@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'2 years', NOW(), false),
('Zied',     'Ben Amor',     'zied.benamor@s2i.tn',       crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'1 year',  NOW(), false),
('Amira',    'Jebali',       'amira.jebali@s2i.tn',       crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'), NOW()-INTERVAL'1 year',  NOW(), false)
ON CONFLICT DO NOTHING;

-- ── §2  Resources (base TCC) ──────────────────────────────────
-- (French original: "Ressources (TCC de base)")
-- A "resource" is the cost side of a user: what one day of that person costs the
-- company. TCC = "taux de coût complet", the fully loaded cost of a day (salary
-- plus every charge on it).
--   daily_rate     : cost of one day, in TND.
--   tcc_rate       : the loading ratio, stored as a fraction — 0.4300 = 43 %.
--   staffing_start : the day the person became billable.
-- Why users and resources are two tables and not one: not every account has a
-- cost (an administrator does not), and MANAGE_RESOURCES guards this data
-- separately — a developer may see his colleagues without seeing what they cost.
-- These are the rates the whole margin engine multiplies, which is why the row
-- is looked up by e-mail: a literal user id would attach a cost price to the
-- wrong person, and every KPI of the application would be wrong with it.
-- Project managers
INSERT INTO resources (user_id, daily_rate, tcc_rate, staffing_start, created_at, updated_at, deleted) VALUES
((SELECT id FROM users WHERE email='momo-chef@pms.local'),      420.00, 0.4300, '2021-03-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='sonia.hammami@s2i.tn'),     450.00, 0.4400, '2019-06-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='aymen.chaouachi@s2i.tn'),   400.00, 0.4200, '2020-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='riadh.mabrouk@s2i.tn'),     430.00, 0.4300, '2019-03-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='wafa.bousbia@s2i.tn'),      390.00, 0.4200, '2020-01-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='tarek.ghannouchi@s2i.tn'),  460.00, 0.4500, '2018-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='ines.dridi@s2i.tn'),        380.00, 0.4200, '2021-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='mehdi.karray@s2i.tn'),      410.00, 0.4300, '2020-03-01', NOW(), NOW(), false)
ON CONFLICT DO NOTHING;

-- Senior developers — the highest daily cost of the three developer levels.
INSERT INTO resources (user_id, daily_rate, tcc_rate, staffing_start, created_at, updated_at, deleted) VALUES
((SELECT id FROM users WHERE email='ahmed.benali@s2i.tn'),      320.00, 0.4400, '2017-03-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='yassine.chebbi@s2i.tn'),    300.00, 0.4300, '2018-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='khaled.turki@s2i.tn'),      350.00, 0.4500, '2016-06-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='marouane.toumi@s2i.tn'),    280.00, 0.4200, '2019-01-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='bilel.nasri@s2i.tn'),       310.00, 0.4400, '2017-09-01', NOW(), NOW(), false)
ON CONFLICT DO NOTHING;

-- Mid-level developers ("confirmés").
INSERT INTO resources (user_id, daily_rate, tcc_rate, staffing_start, created_at, updated_at, deleted) VALUES
((SELECT id FROM users WHERE email='salma.mansouri@s2i.tn'),    230.00, 0.4100, '2020-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='momo-dev@pms.local'),       220.00, 0.4100, '2023-06-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='hamza.bouzid@s2i.tn'),      240.00, 0.4100, '2020-03-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='olfa.benyoussef@s2i.tn'),   210.00, 0.4000, '2021-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='wassim.melki@s2i.tn'),      250.00, 0.4200, '2019-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='asma.benromdhane@s2i.tn'),  225.00, 0.4100, '2020-06-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='nizar.benayed@s2i.tn'),     235.00, 0.4100, '2020-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='anis.louati@s2i.tn'),       215.00, 0.4000, '2021-06-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='firas.bensalah@s2i.tn'),    245.00, 0.4100, '2019-03-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='mourad.ferchichi@s2i.tn'),  220.00, 0.4000, '2021-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='sabrine.chahed@s2i.tn'),    210.00, 0.4000, '2021-09-01', NOW(), NOW(), false)
ON CONFLICT DO NOTHING;

-- Junior developers — the lowest daily cost. The spread between these three
-- levels is what makes the margin of a project depend on WHO is staffed on it,
-- which is the whole point of the KPI screens.
INSERT INTO resources (user_id, daily_rate, tcc_rate, staffing_start, created_at, updated_at, deleted) VALUES
((SELECT id FROM users WHERE email='rim.khalfallah@s2i.tn'),    165.00, 0.3900, '2022-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='sarra.ayari@s2i.tn'),       160.00, 0.3900, '2022-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='emna.gargouri@s2i.tn'),     155.00, 0.3800, '2023-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='hajer.sassi@s2i.tn'),       170.00, 0.3900, '2022-03-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='chiraz.sfar@s2i.tn'),       158.00, 0.3800, '2023-03-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='dorra.hammami@s2i.tn'),     162.00, 0.3900, '2022-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='leila.benfredj@s2i.tn'),    168.00, 0.3900, '2022-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='zied.benamor@s2i.tn'),      155.00, 0.3800, '2023-09-01', NOW(), NOW(), false),
((SELECT id FROM users WHERE email='amira.jebali@s2i.tn'),      160.00, 0.3800, '2023-09-01', NOW(), NOW(), false)
ON CONFLICT DO NOTHING;

-- ── §3  Yearly TCC rates (2023, 2024, 2025) ──────────────────
-- (French original: "TCC Annuels". Each resource gets 3 years of TCC with a
--  progression of about 5 % per year. Shape: resource_id, year, daily_rate,
--  tcc_rate.)
-- WHY THIS TABLE EXISTS AT ALL (V21, spec F-AFF-13 §6.3 rule 4): the cost of one
-- man-day depends on the YEAR it is charged to — the 2024 rate is not the 2025
-- rate. A project that spans three years must therefore value its 2023 days at
-- the 2023 cost. Without these rows, the engine falls back on the single base
-- rate of §2 and re-prices three years of past work at today's salaries, which
-- makes every old project look more expensive than it was.
--
-- Written as a loop rather than 36 × 3 hand-written rows: one rule ("about 5 %
-- less each year going back") applied to whatever §2 contains. A hand-written
-- list would have to be edited every time a resource is added, and a forgotten
-- line would silently mis-price one person.
DO $$
-- RECORD = a variable whose shape is decided by the query that fills it, so the
-- loop does not have to declare one variable per selected column.
DECLARE r RECORD;
BEGIN
  -- FOR ... IN SELECT ... LOOP runs the body once per row returned.
  -- The filter deleted = FALSE keeps soft-deleted resources out: writing rates
  -- for a resource nobody can use would only pollute the reference table.
  FOR r IN SELECT res.id, res.daily_rate, res.tcc_rate, res.staffing_start
           FROM resources res WHERE res.deleted = FALSE
  LOOP
    -- 2025 is the reference year and keeps the base rate untouched; the two past
    -- years are derived from it (× 0.905 and × 0.955), which reproduces a rise
    -- of roughly 5 % a year going forward.
    -- round(..., 2) because the column is NUMERIC(10,2): without it the value
    -- would be rejected or silently rounded, and a cost price ending in three
    -- decimals is not a real salary figure anyway.
    -- The loading ratio moves too, but by a fraction (0.0050 = half a point),
    -- since charges on salaries move far more slowly than the salaries.
    INSERT INTO tcc_annuels (resource_id, annee, daily_rate, tcc_rate, created_at, updated_at, deleted) VALUES
      (r.id, 2023, round(r.daily_rate * 0.905, 2), r.tcc_rate - 0.0050, NOW(), NOW(), false),
      (r.id, 2024, round(r.daily_rate * 0.955, 2), r.tcc_rate - 0.0020, NOW(), NOW(), false),
      (r.id, 2025, r.daily_rate,                   r.tcc_rate,          NOW(), NOW(), false)
    -- The pair (resource_id, annee) is unique (uk_tcc_annuel_resource_annee,
    -- V21). DO NOTHING means: a rate already entered by hand for that year wins
    -- over the one this file would generate.
    ON CONFLICT DO NOTHING;
  END LOOP;
END $$;

-- ── §4  Projects (50) ─────────────────────────────────────────
-- The portfolio. 50 projects spread over 2022-2026 and over four states, so the
-- list screen shows filters that actually filter and the KPI screens have
-- finished projects to compare against running ones.
--   DRAFT     : signed but not started — no workload, no invoice yet.
--   ACTIVE    : running — this is where the monthly EVM review makes sense.
--   COMPLETED : finished — used to show a full history end to end.
-- Columns worth knowing before reading a row:
--   initial_budget / revised_budget : the amount at signature, and the amount
--       after amendments. revised_budget is NULL while nothing has changed, and
--       every computation reads "revised if present, otherwise initial".
--   currency + exchange_rate_to_tnd : a contract can be sold in another currency;
--       the rate is the single conversion point to dinars (ADR-020). Here every
--       project is in TND, so the rate is 1.
--   sold_workload_days : the man-days SOLD. This is the reference the drift of
--       V22 (derive_jh) is measured against, never the internal plan.
--   marge_nette_vendue : the sold net margin baseline added by V22, as a
--       fraction (0.3850 = 38.50 %).
--   director_id / chef_projet_id : looked up by e-mail, never by a literal id,
--       because ids differ from one database to another.
--
-- ── momo-chef's projects (20) ─────────────────────────────────
-- Twenty projects on one manager on purpose: it is the account used for the
-- demonstration, and a portfolio of twenty is what makes the pagination, the
-- filters and the project picker worth showing.
INSERT INTO projects (code, name, description, status, start_date, end_date, initial_budget, revised_budget,
  director_id, chef_projet_id, client, business_model, engagement_type, currency, exchange_rate_to_tnd,
  sold_workload_days, warranty_workload_days, penalty_provision, marge_nette_vendue, created_at, updated_at, deleted)
VALUES
('S2I-2022-001','Portail e-Gouvernement National',
  'Développement d''un portail national e-gouvernement unifié pour la CNI, intégrant l''authentification citoyenne, les services administratifs dématérialisés et le suivi en temps réel.',
  'COMPLETED','2022-03-01','2024-02-29',3800000.00,3800000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Centre National de l''Informatique (CNI)','SEUL','FORFAIT','TND',1,
  14200,420,95000.00,0.3850,'2022-01-15',NOW(),false),

('S2I-2022-002','Transformation Digitale STB',
  'Modernisation complète du système d''information de la STB Bank : core banking, CRM, digital banking et portail clients.',
  'COMPLETED','2022-07-01','2024-06-30',2400000.00,2560000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Société Tunisienne de Banque (STB)','SEUL','FORFAIT','TND',1,
  8800,260,60000.00,0.3700,'2022-04-10',NOW(),false),

('S2I-2023-003','Data Warehouse & BI STEG',
  'Construction d''un entrepôt de données centralisé et d''un tableau de bord décisionnel pour la STEG, couvrant la production, la distribution et la facturation.',
  'COMPLETED','2023-01-15','2024-12-31',1250000.00,1250000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Société Tunisienne de l''Électricité et du Gaz (STEG)','SEUL','FORFAIT','TND',1,
  4600,140,31000.00,0.4020,'2022-11-20',NOW(),false),

('S2I-2023-004','Plateforme Gestion Fiscale DGI',
  'Développement d''une plateforme intégrée de gestion fiscale pour la Direction Générale des Impôts : déclarations en ligne, contrôle fiscal automatisé et suivi des contentieux.',
  'ACTIVE','2023-04-01','2025-03-31',950000.00,1020000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Ministère des Finances — DGI','SEUL','FORFAIT','TND',1,
  3600,110,23750.00,0.3920,'2023-01-10',NOW(),false),

('S2I-2023-005','Portail Tourisme ONTT',
  'Refonte du portail institutionnel de l''ONTT avec espace professionnel, gestion des agences de voyage et module de promotion touristique.',
  'COMPLETED','2023-07-01','2024-06-30',380000.00,380000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Office National du Tourisme Tunisien (ONTT)','SEUL','FORFAIT','TND',1,
  1400,40,9500.00,0.4100,'2023-04-05',NOW(),false),

('S2I-2024-006','SI Assurances STAR',
  'Développement d''un système d''information complet pour STAR Assurances : souscription, sinistres, comptabilité technique et portail agents.',
  'ACTIVE','2024-01-15','2025-07-31',680000.00,680000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'STAR Assurances','SEUL','FORFAIT','TND',1,
  2600,78,17000.00,0.3950,'2023-10-20',NOW(),false),

('S2I-2024-007','BI Analytics BH Bank',
  'Mise en place d''une solution BI complète pour BH Bank : datamart crédits immobiliers, tableaux de bord IFRS 9 et reporting réglementaire BCT.',
  'ACTIVE','2024-02-01','2025-04-30',520000.00,520000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'BH Bank','SEUL','FORFAIT','TND',1,
  1950,58,13000.00,0.3880,'2023-11-15',NOW(),false),

('S2I-2024-008','CRM Orange Tunisie',
  'Déploiement et customisation d''un CRM 360° pour Orange Tunisie : parcours client omnicanal, automatisation marketing et centre d''appel intégré.',
  'ACTIVE','2024-03-01','2025-06-30',490000.00,490000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Orange Tunisie','SEUL','FORFAIT','TND',1,
  1850,55,12250.00,0.3820,'2024-01-08',NOW(),false),

('S2I-2024-009','Système Gestion Universitaire UTT',
  'Développement d''un ERP académique pour l''Université de Tunis el Manar : scolarité, examens, stages, recherche et plateforme pédagogique.',
  'ACTIVE','2024-04-01','2025-03-31',350000.00,350000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Université de Tunis','SEUL','FORFAIT','TND',1,
  1350,40,8750.00,0.3900,'2024-01-22',NOW(),false),

('S2I-2024-010','SI SONEDE',
  'Développement d''un système d''information technique pour la SONEDE : gestion du réseau, incidents, maintenance préventive et facturation clients.',
  'ACTIVE','2024-05-01','2025-10-31',410000.00,410000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'SONEDE','SEUL','FORFAIT','TND',1,
  1600,48,10250.00,0.3970,'2024-02-14',NOW(),false),

('S2I-2024-011','Plateforme CNAM 360',
  'Développement d''une plateforme intégrée pour la CNAM : affiliation, remboursements, carte santé électronique et portail médecin.',
  'ACTIVE','2024-06-01','2026-05-31',580000.00,615000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Caisse Nationale d''Assurance Maladie (CNAM)','SEUL','FORFAIT','TND',1,
  2200,66,14500.00,0.3830,'2024-03-10',NOW(),false),

('S2I-2024-012','Module Reporting Amen Bank',
  'Développement d''un module de reporting réglementaire automatisé pour Amen Bank : états financiers, ratios prudentiels et reporting BCT.',
  'COMPLETED','2024-07-01','2025-01-31',155000.00,155000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Amen Bank','SEUL','FORFAIT','TND',1,
  580,18,3875.00,0.4050,'2024-04-18',NOW(),false),

('S2I-2024-013','Application Mobile CNAM',
  'Développement d''une application mobile (iOS/Android) pour les assurés CNAM : consultation droits, suivi remboursements, recherche prestataires.',
  'COMPLETED','2024-08-01','2025-02-28',130000.00,130000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'CNAM','SEUL','FORFAIT','TND',1,
  480,14,3250.00,0.4120,'2024-05-20',NOW(),false),

('S2I-2024-014','BI Dashboard STEG Avancé',
  'Extension du Data Warehouse STEG avec des fonctionnalités avancées de machine learning pour la prédiction de la demande énergétique.',
  'COMPLETED','2024-09-01','2025-03-31',175000.00,175000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'STEG','SEUL','FORFAIT','TND',1,
  650,20,4375.00,0.4000,'2024-06-12',NOW(),false),

('S2I-2025-015','Système Ticketing SONEDE',
  'Développement d''un système de gestion des tickets d''incident et de la maintenance corrective pour les équipes terrain de la SONEDE.',
  'ACTIVE','2025-01-15','2025-09-30',140000.00,140000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'SONEDE','SEUL','FORFAIT','TND',1,
  530,16,3500.00,0.4050,'2024-11-05',NOW(),false),

('S2I-2025-016','Portail Adhérents CNAM',
  'Développement d''un portail self-service pour les adhérents CNAM : gestion des remboursements, téléconsultation et prescription électronique.',
  'ACTIVE','2025-02-01','2025-12-31',185000.00,185000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'CNAM','SEUL','FORFAIT','TND',1,
  700,21,4625.00,0.4100,'2024-11-20',NOW(),false),

('S2I-2025-017','Module Audit BH Bank',
  'Développement d''un module d''audit interne et de contrôle permanent pour BH Bank : cartographie des risques, plans d''action et reporting comité.',
  'ACTIVE','2025-03-01','2026-02-28',165000.00,165000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'BH Bank','SEUL','FORFAIT','TND',1,
  620,19,4125.00,0.4020,'2025-01-10',NOW(),false),

('S2I-2025-018','Tableau de Bord Sofrecom',
  'Développement d''un tableau de bord de pilotage de la performance opérationnelle et financière pour Sofrecom Tunisie.',
  'ACTIVE','2025-04-01','2025-12-31',130000.00,130000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'Sofrecom Tunisie','SEUL','FORFAIT','TND',1,
  490,15,3250.00,0.4150,'2025-01-25',NOW(),false),

('S2I-2025-019','Portail RH BIAT',
  'Développement d''un portail RH digital pour BIAT : gestion des talents, e-recrutement, formation en ligne et gestion des absences.',
  'ACTIVE','2025-05-01','2026-04-30',195000.00,195000.00,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'BIAT','SEUL','FORFAIT','TND',1,
  740,22,4875.00,0.4080,'2025-02-14',NOW(),false),

('S2I-2025-020','Plateforme E-Learning CNI',
  'Développement d''une plateforme de formation en ligne pour les agents de la fonction publique, gérée par la CNI : cursus, certifications et suivi des compétences.',
  'DRAFT','2025-06-01','2026-05-31',210000.00,NULL,
  (SELECT id FROM users WHERE email='momo-directeur@pms.local'),(SELECT id FROM users WHERE email='momo-chef@pms.local'),
  'CNI','SEUL','FORFAIT','TND',1,
  800,24,5250.00,0.4000,'2025-03-20',NOW(),false)
ON CONFLICT DO NOTHING;

-- ── Projects of the other project managers (30) ───────────────
-- Split between the seven other managers, so that every screen which filters "my
-- projects" has something to leave out — and so the project scope rule of
-- ADR-021 can be demonstrated: a manager who opens the URL of a project that is
-- not his gets 403 even though he holds the permission.
-- Note these statements do not list penalty_provision, while momo-chef's do: a
-- column left out of the INSERT simply takes its default (NULL here), which is
-- the honest way of saying "no penalty clause was recorded for this contract".
-- Sonia Hammami (6 projects)
INSERT INTO projects (code, name, description, status, start_date, end_date, initial_budget, revised_budget,
  director_id, chef_projet_id, client, business_model, engagement_type, currency, exchange_rate_to_tnd,
  sold_workload_days, warranty_workload_days, marge_nette_vendue, created_at, updated_at, deleted)
VALUES
('S2I-2022-021','Transformation Digitale BIAT',
  'Modernisation du SI de la BIAT : migration core banking, plateforme digital banking et refonte du réseau d''agences.',
  'COMPLETED','2022-01-01','2024-06-30',2100000.00,2250000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='sonia.hammami@s2i.tn'),
  'BIAT','SEUL','FORFAIT','TND',1, 7800,234,0.3720,'2021-10-15',NOW(),false),
('S2I-2023-022','Smart City Tunis',
  'Plateforme intégrée de gestion urbaine intelligente pour la commune de Tunis : mobilité, énergie, déchets, sécurité et services citoyens.',
  'ACTIVE','2023-03-01','2026-02-28',3200000.00,3200000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='sonia.hammami@s2i.tn'),
  'Commune de Tunis','GROUPEMENT','FORFAIT','TND',1, 12000,360,0.3800,'2022-11-20',NOW(),false),
('S2I-2024-023','AI Document Processing Poulina',
  'Développement d''une solution d''intelligence artificielle pour l''automatisation du traitement documentaire au sein du groupe Poulina.',
  'ACTIVE','2024-01-01','2025-06-30',650000.00,650000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='sonia.hammami@s2i.tn'),
  'Poulina Group Holding','SEUL','FORFAIT','TND',1, 2450,74,0.4200,'2023-09-10',NOW(),false),
('S2I-2024-024','CRM Digital Amen Bank',
  'Déploiement d''un CRM bancaire 360° pour Amen Bank avec gestion des opportunités, scoring client et automatisation des campagnes.',
  'ACTIVE','2024-06-01','2026-05-31',1450000.00,1450000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='sonia.hammami@s2i.tn'),
  'Amen Bank','SEUL','FORFAIT','TND',1, 5400,162,0.3900,'2024-02-28',NOW(),false),
('S2I-2025-025','BI Reporting Telnet',
  'Mise en place d''un système de reporting décisionnel pour Telnet : consolidation des données de production, RH et finances.',
  'ACTIVE','2025-01-01','2025-09-30',145000.00,145000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='sonia.hammami@s2i.tn'),
  'Telnet','SEUL','FORFAIT','TND',1, 540,16,0.4150,'2024-10-05',NOW(),false),
('S2I-2025-026','Application Mobile STB',
  'Développement d''une application mobile nouvelle génération pour STB Bank : paiements, virements, épargne et espace crédit.',
  'ACTIVE','2025-03-01','2025-12-31',160000.00,160000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='sonia.hammami@s2i.tn'),
  'STB Bank','SEUL','FORFAIT','TND',1, 600,18,0.4100,'2024-12-15',NOW(),false)
ON CONFLICT DO NOTHING;

-- Aymen Chaouachi (5 projects)
INSERT INTO projects (code, name, description, status, start_date, end_date, initial_budget, revised_budget,
  director_id, chef_projet_id, client, business_model, engagement_type, currency, exchange_rate_to_tnd,
  sold_workload_days, warranty_workload_days, marge_nette_vendue, created_at, updated_at, deleted)
VALUES
('S2I-2022-027','Migration ERP National MF',
  'Migration du système ERP du Ministère des Finances vers une solution moderne intégrant la GFP, la trésorerie et le contrôle des engagements.',
  'ACTIVE','2022-09-01','2025-08-31',3200000.00,3450000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='aymen.chaouachi@s2i.tn'),
  'Ministère des Finances','GROUPEMENT','FORFAIT','TND',1, 11800,354,0.3680,'2022-05-20',NOW(),false),
('S2I-2023-028','Système Information Hospitalier',
  'Déploiement d''un SIH complet dans 12 hôpitaux régionaux : dossier patient électronique, imagerie médicale, pharmacie et facturation.',
  'ACTIVE','2023-06-01','2026-05-31',1800000.00,1800000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='aymen.chaouachi@s2i.tn'),
  'Ministère de la Santé','GROUPEMENT','FORFAIT','TND',1, 6700,200,0.3750,'2023-01-18',NOW(),false),
('S2I-2024-029','Portail Port de Radès',
  'Digitalisation des procédures douanières et logistiques du Port de Radès : manifestes, escales, prise en charge et livraison de conteneurs.',
  'ACTIVE','2024-02-01','2025-07-31',730000.00,730000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='aymen.chaouachi@s2i.tn'),
  'Port de Radès — OMMP','SEUL','FORFAIT','TND',1, 2750,83,0.3920,'2023-11-10',NOW(),false),
('S2I-2024-030','API Integration Ooredoo',
  'Développement d''une couche d''API REST pour l''intégration des systèmes Ooredoo avec les partenaires (banques, e-commerce, gouvernement).',
  'COMPLETED','2024-10-01','2025-06-30',240000.00,240000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='aymen.chaouachi@s2i.tn'),
  'Ooredoo Tunisie','SEUL','FORFAIT','TND',1, 900,27,0.4150,'2024-07-14',NOW(),false),
('S2I-2025-031','Assurance Qualité Délice',
  'Développement d''un système de gestion de la qualité pour le groupe Délice : traçabilité produit, contrôle qualité et certification ISO.',
  'ACTIVE','2025-02-01','2025-12-31',320000.00,320000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='aymen.chaouachi@s2i.tn'),
  'Délice Holding','SEUL','FORFAIT','TND',1, 1200,36,0.4080,'2024-11-22',NOW(),false)
ON CONFLICT DO NOTHING;

-- Riadh Mabrouk (5 projects)
INSERT INTO projects (code, name, description, status, start_date, end_date, initial_budget, revised_budget,
  director_id, chef_projet_id, client, business_model, engagement_type, currency, exchange_rate_to_tnd,
  sold_workload_days, warranty_workload_days, marge_nette_vendue, created_at, updated_at, deleted)
VALUES
('S2I-2023-032','CRM Modernisation Ooredoo',
  'Refonte du CRM Ooredoo Tunisie : migration vers Salesforce, intégration avec le BSS/OSS et automatisation du service client.',
  'COMPLETED','2023-01-01','2024-12-31',850000.00,920000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='riadh.mabrouk@s2i.tn'),
  'Ooredoo Tunisie','SEUL','FORFAIT','TND',1, 3200,96,0.3810,'2022-09-15',NOW(),false),
('S2I-2023-033','Plateforme RH Digitale Telnet',
  'Développement d''un SIRH complet pour Telnet Holding : gestion des talents, paie, formation et mobilité internationale.',
  'ACTIVE','2023-06-01','2025-05-31',620000.00,620000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='riadh.mabrouk@s2i.tn'),
  'Telnet Holding','SEUL','FORFAIT','TND',1, 2300,69,0.3920,'2023-02-28',NOW(),false),
('S2I-2024-034','Douanes Information System',
  'Refonte du système d''information douanier tunisien : dédouanement électronique, suivi des marchandises et interconnexion avec les partenaires douaniers.',
  'ACTIVE','2024-01-15','2025-12-31',720000.00,720000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='riadh.mabrouk@s2i.tn'),
  'Direction Générale des Douanes','SEUL','FORFAIT','TND',1, 2700,81,0.3880,'2023-10-05',NOW(),false),
('S2I-2024-035','Portail Clients Orange Tunisie',
  'Refonte du portail client Orange Tunisie : espace abonné, souscription en ligne, gestion des réclamations et fidélisation.',
  'COMPLETED','2024-08-01','2025-04-30',260000.00,260000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='riadh.mabrouk@s2i.tn'),
  'Orange Tunisie','SEUL','FORFAIT','TND',1, 980,29,0.4050,'2024-05-20',NOW(),false),
('S2I-2025-036','CRM Intégration Ooredoo',
  'Intégration du nouveau CRM Ooredoo avec les canaux digitaux (app mobile, WhatsApp Business) et les outils de marketing automation.',
  'ACTIVE','2025-01-01','2026-06-30',490000.00,490000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='riadh.mabrouk@s2i.tn'),
  'Ooredoo Tunisie','SEUL','FORFAIT','TND',1, 1850,56,0.4010,'2024-10-18',NOW(),false)
ON CONFLICT DO NOTHING;

-- Wafa Bousbia (4 projects)
INSERT INTO projects (code, name, description, status, start_date, end_date, initial_budget, revised_budget,
  director_id, chef_projet_id, client, business_model, engagement_type, currency, exchange_rate_to_tnd,
  sold_workload_days, warranty_workload_days, marge_nette_vendue, created_at, updated_at, deleted)
VALUES
('S2I-2023-037','Système Aéroport OACA',
  'Développement d''un système de gestion aéroportuaire pour l''OACA : escales, handling, facturation compagnies et sûreté.',
  'ACTIVE','2023-09-01','2025-08-31',780000.00,780000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='wafa.bousbia@s2i.tn'),
  'Office de l''Aviation Civile et des Aéroports (OACA)','SEUL','FORFAIT','TND',1, 2900,87,0.3850,'2023-05-15',NOW(),false),
('S2I-2024-038','Intranet Vermeg',
  'Développement d''un portail intranet et d''un outil de gestion des connaissances pour Vermeg : KM, collaboration et onboarding.',
  'COMPLETED','2024-03-01','2024-11-30',200000.00,200000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='wafa.bousbia@s2i.tn'),
  'Vermeg','SEUL','FORFAIT','TND',1, 750,23,0.4120,'2024-01-08',NOW(),false),
('S2I-2025-039','Module Formation Délice',
  'Développement d''un LMS (Learning Management System) pour le groupe Délice : e-learning, évaluation des compétences et suivi des formations réglementaires.',
  'ACTIVE','2025-01-01','2025-09-30',110000.00,110000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='wafa.bousbia@s2i.tn'),
  'Délice Holding','SEUL','FORFAIT','TND',1, 415,12,0.4200,'2024-10-14',NOW(),false),
('S2I-2025-040','Suivi Projets Vermeg',
  'Développement d''un outil de gestion de portefeuille de projets internes pour Vermeg : planification, ressources et reporting.',
  'ACTIVE','2025-04-01','2025-12-31',90000.00,90000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='wafa.bousbia@s2i.tn'),
  'Vermeg','SEUL','FORFAIT','TND',1, 340,10,0.4250,'2025-02-10',NOW(),false)
ON CONFLICT DO NOTHING;

-- Tarek Ghannouchi (4 projects)
INSERT INTO projects (code, name, description, status, start_date, end_date, initial_budget, revised_budget,
  director_id, chef_projet_id, client, business_model, engagement_type, currency, exchange_rate_to_tnd,
  sold_workload_days, warranty_workload_days, marge_nette_vendue, created_at, updated_at, deleted)
VALUES
('S2I-2022-041','API Integration STB',
  'Développement d''une couche API Gateway pour la STB Bank permettant l''intégration avec les Fintechs et les services du gouvernement électronique.',
  'COMPLETED','2022-06-01','2023-03-31',210000.00,210000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='tarek.ghannouchi@s2i.tn'),
  'STB Bank','SEUL','FORFAIT','TND',1, 790,24,0.4000,'2022-03-20',NOW(),false),
('S2I-2023-042','Mobile App Ooredoo',
  'Refonte complète de l''application mobile Ooredoo Tunisie (iOS/Android) : nouvelle UX/UI, intégration paiement mobile et services à valeur ajoutée.',
  'COMPLETED','2023-04-01','2024-01-31',185000.00,185000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='tarek.ghannouchi@s2i.tn'),
  'Ooredoo Tunisie','SEUL','FORFAIT','TND',1, 700,21,0.4100,'2023-01-10',NOW(),false),
('S2I-2024-043','Dashboard Analytics BIAT',
  'Développement d''un tableau de bord analytique en temps réel pour les directeurs régionaux de la BIAT : performance commerciale et pilotage des risques.',
  'COMPLETED','2024-01-01','2024-09-30',145000.00,145000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='tarek.ghannouchi@s2i.tn'),
  'BIAT','SEUL','FORFAIT','TND',1, 545,16,0.4150,'2023-10-05',NOW(),false),
('S2I-2025-044','API Gateway Tunisie Telecom',
  'Développement d''une plateforme API Management pour Tunisie Telecom : monétisation des APIs, sécurité et portail développeurs.',
  'ACTIVE','2025-02-01','2025-10-31',220000.00,220000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='tarek.ghannouchi@s2i.tn'),
  'Tunisie Telecom','SEUL','FORFAIT','TND',1, 830,25,0.4050,'2024-11-25',NOW(),false)
ON CONFLICT DO NOTHING;

-- Inès Dridi (3 projects)
INSERT INTO projects (code, name, description, status, start_date, end_date, initial_budget, revised_budget,
  director_id, chef_projet_id, client, business_model, engagement_type, currency, exchange_rate_to_tnd,
  sold_workload_days, warranty_workload_days, marge_nette_vendue, created_at, updated_at, deleted)
VALUES
('S2I-2024-045','Portail Adhérents BIAT',
  'Développement d''un portail self-service digital pour les clients entreprises de la BIAT : gestion de compte, virements de masse et reporting.',
  'ACTIVE','2024-05-01','2025-04-30',195000.00,195000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='ines.dridi@s2i.tn'),
  'BIAT','SEUL','FORFAIT','TND',1, 735,22,0.4000,'2024-02-18',NOW(),false),
('S2I-2025-046','Gestion Contrats Min. Santé',
  'Développement d''un système de gestion des contrats et marchés publics pour le Ministère de la Santé : appels d''offres, suivi et exécution.',
  'ACTIVE','2025-01-01','2025-12-31',280000.00,280000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='ines.dridi@s2i.tn'),
  'Ministère de la Santé','SEUL','FORFAIT','TND',1, 1060,32,0.3950,'2024-10-08',NOW(),false),
('S2I-2025-047','Extranet Fournisseurs Telnet',
  'Développement d''un portail fournisseurs pour Telnet : commandes, livraisons, facturation électronique et évaluation des performances.',
  'ACTIVE','2025-04-01','2026-01-31',125000.00,125000.00,
  (SELECT id FROM users WHERE email='karim.bensalah@s2i.tn'),(SELECT id FROM users WHERE email='ines.dridi@s2i.tn'),
  'Telnet','SEUL','FORFAIT','TND',1, 470,14,0.4200,'2025-01-20',NOW(),false)
ON CONFLICT DO NOTHING;

-- Mehdi Karray (3 projects)
INSERT INTO projects (code, name, description, status, start_date, end_date, initial_budget, revised_budget,
  director_id, chef_projet_id, client, business_model, engagement_type, currency, exchange_rate_to_tnd,
  sold_workload_days, warranty_workload_days, marge_nette_vendue, created_at, updated_at, deleted)
VALUES
('S2I-2023-048','ERP Poulina Group',
  'Déploiement et customisation d''un ERP (SAP S/4HANA) pour Poulina Group Holding : finance, supply chain, production et RH.',
  'ACTIVE','2023-10-01','2025-09-30',890000.00,950000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='mehdi.karray@s2i.tn'),
  'Poulina Group Holding','GROUPEMENT','FORFAIT','TND',1, 3350,101,0.3900,'2023-06-15',NOW(),false),
('S2I-2024-049','Maintenance SI STEG',
  'TMA (Tierce Maintenance Applicative) du système d''information de la STEG : correction de bugs, évolutions mineures et supervision.',
  'COMPLETED','2024-06-01','2025-05-31',300000.00,300000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='mehdi.karray@s2i.tn'),
  'STEG','SEUL','REGIE','TND',1, 1130,34,0.3850,'2024-03-10',NOW(),false),
('S2I-2025-050','Refonte Site Web ONTT',
  'Refonte complète du site institutionnel de l''ONTT : nouvelle charte graphique, multilinguisme, référencement et intégration réseaux sociaux.',
  'ACTIVE','2025-03-01','2025-11-30',95000.00,95000.00,
  (SELECT id FROM users WHERE email='nadia.trabelsi@s2i.tn'),(SELECT id FROM users WHERE email='mehdi.karray@s2i.tn'),
  'ONTT','SEUL','FORFAIT','TND',1, 360,11,0.4300,'2025-01-05',NOW(),false)
ON CONFLICT DO NOTHING;

-- ── §5  Team assignments ──────────────────────────────────────
-- (French original: "Affectations équipe")
-- Who works on which project. This table is not decoration: it is what the
-- project scope rule of ADR-021 reads. ProjectScopeInterceptor lets a developer
-- through on /api/projects/{id}/** only if a row here ties him to that project.
-- Without this section every demo account would get 403 on every project screen,
-- and the whole application would look broken.
--
-- Projects of momo-chef — developers
-- Reading the statement: INSERT ... SELECT writes one row per row the SELECT
-- returns, so this single statement creates a hundred-odd assignments.
-- "FROM projects p, users u" is a CROSS JOIN: every project paired with every
-- user. "WHERE (p.code, u.email) IN ( ... )" then keeps only the pairs listed
-- below — this is a ROW comparison, so each entry matches one project code AND
-- one e-mail together.
-- Why written this way: the list stays readable as (project, person) couples and
-- carries no id, so it survives on any database. Pairing on the code and the
-- e-mail instead of ids is what makes that possible.
INSERT INTO team_assignments (project_id, user_id, role_in_team, start_date, end_date, created_at, updated_at, deleted)
-- The assignment runs for the whole life of the project, so the dates are copied
-- from the project itself rather than invented.
SELECT p.id, u.id, 'DEVELOPPEUR', p.start_date, p.end_date, NOW(), NOW(), false
FROM projects p, users u
WHERE (p.code, u.email) IN (
  ('S2I-2022-001','ahmed.benali@s2i.tn'),('S2I-2022-001','yassine.chebbi@s2i.tn'),
  ('S2I-2022-001','khaled.turki@s2i.tn'),('S2I-2022-001','marouane.toumi@s2i.tn'),
  ('S2I-2022-001','bilel.nasri@s2i.tn'), ('S2I-2022-001','salma.mansouri@s2i.tn'),
  ('S2I-2022-001','hamza.bouzid@s2i.tn'),('S2I-2022-001','olfa.benyoussef@s2i.tn'),
  ('S2I-2022-001','wassim.melki@s2i.tn'),('S2I-2022-001','asma.benromdhane@s2i.tn'),
  ('S2I-2022-002','ahmed.benali@s2i.tn'),('S2I-2022-002','yassine.chebbi@s2i.tn'),
  ('S2I-2022-002','khaled.turki@s2i.tn'),('S2I-2022-002','bilel.nasri@s2i.tn'),
  ('S2I-2022-002','nizar.benayed@s2i.tn'),('S2I-2022-002','anis.louati@s2i.tn'),
  ('S2I-2022-002','firas.bensalah@s2i.tn'),('S2I-2022-002','rim.khalfallah@s2i.tn'),
  ('S2I-2023-003','marouane.toumi@s2i.tn'),('S2I-2023-003','hamza.bouzid@s2i.tn'),
  ('S2I-2023-003','wassim.melki@s2i.tn'),('S2I-2023-003','asma.benromdhane@s2i.tn'),
  ('S2I-2023-003','sarra.ayari@s2i.tn'),('S2I-2023-003','emna.gargouri@s2i.tn'),
  ('S2I-2023-004','ahmed.benali@s2i.tn'),('S2I-2023-004','yassine.chebbi@s2i.tn'),
  ('S2I-2023-004','bilel.nasri@s2i.tn'),('S2I-2023-004','nizar.benayed@s2i.tn'),
  ('S2I-2023-004','hajer.sassi@s2i.tn'),('S2I-2023-004','chiraz.sfar@s2i.tn'),
  ('S2I-2023-004','dorra.hammami@s2i.tn'),
  ('S2I-2023-005','salma.mansouri@s2i.tn'),('S2I-2023-005','hamza.bouzid@s2i.tn'),
  ('S2I-2023-005','rim.khalfallah@s2i.tn'),('S2I-2023-005','sarra.ayari@s2i.tn'),
  -- S2I-2024-006 : momo-dev + 5 devs
  ('S2I-2024-006','momo-dev@pms.local'),('S2I-2024-006','khaled.turki@s2i.tn'),
  ('S2I-2024-006','marouane.toumi@s2i.tn'),('S2I-2024-006','wassim.melki@s2i.tn'),
  ('S2I-2024-006','asma.benromdhane@s2i.tn'),('S2I-2024-006','sabrine.chahed@s2i.tn'),
  -- S2I-2024-007 : momo-dev + 4 devs
  ('S2I-2024-007','momo-dev@pms.local'),('S2I-2024-007','ahmed.benali@s2i.tn'),
  ('S2I-2024-007','bilel.nasri@s2i.tn'),('S2I-2024-007','emna.gargouri@s2i.tn'),
  ('S2I-2024-007','hajer.sassi@s2i.tn'),
  -- S2I-2024-008 : momo-dev + 4 devs
  ('S2I-2024-008','momo-dev@pms.local'),('S2I-2024-008','yassine.chebbi@s2i.tn'),
  ('S2I-2024-008','nizar.benayed@s2i.tn'),('S2I-2024-008','chiraz.sfar@s2i.tn'),
  ('S2I-2024-008','dorra.hammami@s2i.tn'),
  ('S2I-2024-009','hamza.bouzid@s2i.tn'),('S2I-2024-009','olfa.benyoussef@s2i.tn'),
  ('S2I-2024-009','rim.khalfallah@s2i.tn'),('S2I-2024-009','leila.benfredj@s2i.tn'),
  -- S2I-2024-010 : momo-dev + 4 devs
  ('S2I-2024-010','momo-dev@pms.local'),('S2I-2024-010','khaled.turki@s2i.tn'),
  ('S2I-2024-010','salma.mansouri@s2i.tn'),('S2I-2024-010','anis.louati@s2i.tn'),
  ('S2I-2024-010','zied.benamor@s2i.tn'),
  -- S2I-2024-011 : momo-dev + 5 devs
  ('S2I-2024-011','momo-dev@pms.local'),('S2I-2024-011','marouane.toumi@s2i.tn'),
  ('S2I-2024-011','bilel.nasri@s2i.tn'),('S2I-2024-011','wassim.melki@s2i.tn'),
  ('S2I-2024-011','sabrine.chahed@s2i.tn'),('S2I-2024-011','amira.jebali@s2i.tn'),
  ('S2I-2024-012','sarra.ayari@s2i.tn'),('S2I-2024-012','emna.gargouri@s2i.tn'),
  ('S2I-2024-013','rim.khalfallah@s2i.tn'),('S2I-2024-013','chiraz.sfar@s2i.tn'),
  ('S2I-2024-014','hajer.sassi@s2i.tn'),('S2I-2024-014','dorra.hammami@s2i.tn'),
  -- S2I-2025-015 : momo-dev + 2 devs
  ('S2I-2025-015','momo-dev@pms.local'),('S2I-2025-015','leila.benfredj@s2i.tn'),
  ('S2I-2025-015','zied.benamor@s2i.tn'),
  -- S2I-2025-016 : momo-dev + 2 devs
  ('S2I-2025-016','momo-dev@pms.local'),('S2I-2025-016','amira.jebali@s2i.tn'),
  ('S2I-2025-016','sabrine.chahed@s2i.tn'),
  -- S2I-2025-017 : momo-dev + 2 devs
  ('S2I-2025-017','momo-dev@pms.local'),('S2I-2025-017','firas.bensalah@s2i.tn'),
  ('S2I-2025-017','anis.louati@s2i.tn'),
  -- S2I-2025-018 : momo-dev + 2 devs
  ('S2I-2025-018','momo-dev@pms.local'),('S2I-2025-018','mourad.ferchichi@s2i.tn'),
  ('S2I-2025-018','leila.benfredj@s2i.tn'),
  -- S2I-2025-019 : momo-dev + 2 devs
  ('S2I-2025-019','momo-dev@pms.local'),('S2I-2025-019','zied.benamor@s2i.tn'),
  ('S2I-2025-019','amira.jebali@s2i.tn'),
  ('S2I-2025-020','firas.bensalah@s2i.tn'),('S2I-2025-020','mourad.ferchichi@s2i.tn')
)
ON CONFLICT DO NOTHING;

-- momo-chef is himself the manager of his twenty projects, with role_in_team
-- CHEF_PROJET.
-- Why a row is needed even though projects.chef_projet_id already names him:
-- the scope check reads team_assignments, so without this row the manager of the
-- project would be refused access to his own project.
-- role_in_team is the role INSIDE this project, not the RBAC role of the account.
-- The same person can be manager here and developer elsewhere; what he is
-- allowed to do still comes from his permissions, never from this text.
INSERT INTO team_assignments (project_id, user_id, role_in_team, start_date, end_date, created_at, updated_at, deleted)
SELECT p.id, (SELECT id FROM users WHERE email='momo-chef@pms.local'), 'CHEF_PROJET', p.start_date, p.end_date, NOW(), NOW(), false
FROM projects p
WHERE p.code IN ('S2I-2022-001','S2I-2022-002','S2I-2023-003','S2I-2023-004','S2I-2023-005',
                 'S2I-2024-006','S2I-2024-007','S2I-2024-008','S2I-2024-009','S2I-2024-010',
                 'S2I-2024-011','S2I-2024-012','S2I-2024-013','S2I-2024-014','S2I-2025-015',
                 'S2I-2025-016','S2I-2025-017','S2I-2025-018','S2I-2025-019','S2I-2025-020')
ON CONFLICT DO NOTHING;

-- The other managers, each one assigned to his own projects.
-- Here the user is not named at all: p.chef_projet_id is read straight from the
-- project row. One statement covers the thirty projects and can never disagree
-- with the project sheet, which a hand-written list of thirty couples could.
INSERT INTO team_assignments (project_id, user_id, role_in_team, start_date, end_date, created_at, updated_at, deleted)
SELECT p.id, p.chef_projet_id, 'CHEF_PROJET', p.start_date, p.end_date, NOW(), NOW(), false
FROM projects p
WHERE p.code IN ('S2I-2022-021','S2I-2023-022','S2I-2024-023','S2I-2024-024','S2I-2025-025','S2I-2025-026',
                 'S2I-2022-027','S2I-2023-028','S2I-2024-029','S2I-2024-030','S2I-2025-031',
                 'S2I-2023-032','S2I-2023-033','S2I-2024-034','S2I-2024-035','S2I-2025-036',
                 'S2I-2023-037','S2I-2024-038','S2I-2025-039','S2I-2025-040',
                 'S2I-2022-041','S2I-2023-042','S2I-2024-043','S2I-2025-044',
                 'S2I-2024-045','S2I-2025-046','S2I-2025-047',
                 'S2I-2023-048','S2I-2024-049','S2I-2025-050')
ON CONFLICT DO NOTHING;

-- Developers on the projects of the other managers. Same (project code, e-mail)
-- pairing as the first block. Note that several developers appear on several
-- projects at once: that overlap is what gives the workload screens something to
-- show, since a person spread over four projects is exactly the case a manager
-- needs to see.
INSERT INTO team_assignments (project_id, user_id, role_in_team, start_date, end_date, created_at, updated_at, deleted)
SELECT p.id, u.id, 'DEVELOPPEUR', p.start_date, p.end_date, NOW(), NOW(), false
FROM projects p, users u
WHERE (p.code, u.email) IN (
  ('S2I-2022-021','ahmed.benali@s2i.tn'),('S2I-2022-021','yassine.chebbi@s2i.tn'),
  ('S2I-2022-021','khaled.turki@s2i.tn'),('S2I-2022-021','bilel.nasri@s2i.tn'),
  ('S2I-2022-021','hamza.bouzid@s2i.tn'),('S2I-2022-021','salma.mansouri@s2i.tn'),
  ('S2I-2022-021','nizar.benayed@s2i.tn'),('S2I-2022-021','anis.louati@s2i.tn'),
  ('S2I-2023-022','ahmed.benali@s2i.tn'),('S2I-2023-022','yassine.chebbi@s2i.tn'),
  ('S2I-2023-022','khaled.turki@s2i.tn'),('S2I-2023-022','marouane.toumi@s2i.tn'),
  ('S2I-2023-022','bilel.nasri@s2i.tn'),('S2I-2023-022','wassim.melki@s2i.tn'),
  ('S2I-2023-022','firas.bensalah@s2i.tn'),('S2I-2023-022','mourad.ferchichi@s2i.tn'),
  ('S2I-2023-022','rim.khalfallah@s2i.tn'),('S2I-2023-022','sarra.ayari@s2i.tn'),
  ('S2I-2024-023','marouane.toumi@s2i.tn'),('S2I-2024-023','wassim.melki@s2i.tn'),
  ('S2I-2024-023','firas.bensalah@s2i.tn'),('S2I-2024-023','mourad.ferchichi@s2i.tn'),
  ('S2I-2024-024','ahmed.benali@s2i.tn'),('S2I-2024-024','khaled.turki@s2i.tn'),
  ('S2I-2024-024','bilel.nasri@s2i.tn'),('S2I-2024-024','asma.benromdhane@s2i.tn'),
  ('S2I-2024-024','hajer.sassi@s2i.tn'),('S2I-2024-024','dorra.hammami@s2i.tn'),
  ('S2I-2025-025','sarra.ayari@s2i.tn'),('S2I-2025-025','emna.gargouri@s2i.tn'),
  ('S2I-2025-026','rim.khalfallah@s2i.tn'),('S2I-2025-026','chiraz.sfar@s2i.tn'),
  ('S2I-2022-027','ahmed.benali@s2i.tn'),('S2I-2022-027','yassine.chebbi@s2i.tn'),
  ('S2I-2022-027','khaled.turki@s2i.tn'),('S2I-2022-027','marouane.toumi@s2i.tn'),
  ('S2I-2022-027','bilel.nasri@s2i.tn'),('S2I-2022-027','salma.mansouri@s2i.tn'),
  ('S2I-2022-027','hamza.bouzid@s2i.tn'),('S2I-2022-027','wassim.melki@s2i.tn'),
  ('S2I-2022-027','nizar.benayed@s2i.tn'),('S2I-2022-027','anis.louati@s2i.tn'),
  ('S2I-2023-028','ahmed.benali@s2i.tn'),('S2I-2023-028','khaled.turki@s2i.tn'),
  ('S2I-2023-028','bilel.nasri@s2i.tn'),('S2I-2023-028','asma.benromdhane@s2i.tn'),
  ('S2I-2023-028','sabrine.chahed@s2i.tn'),('S2I-2023-028','leila.benfredj@s2i.tn'),
  ('S2I-2023-028','zied.benamor@s2i.tn'),
  ('S2I-2024-029','marouane.toumi@s2i.tn'),('S2I-2024-029','hamza.bouzid@s2i.tn'),
  ('S2I-2024-029','firas.bensalah@s2i.tn'),('S2I-2024-029','hajer.sassi@s2i.tn'),
  ('S2I-2024-030','nizar.benayed@s2i.tn'),('S2I-2024-030','chiraz.sfar@s2i.tn'),
  ('S2I-2025-031','dorra.hammami@s2i.tn'),('S2I-2025-031','amira.jebali@s2i.tn'),
  ('S2I-2025-031','sarra.ayari@s2i.tn'),
  ('S2I-2023-032','yassine.chebbi@s2i.tn'),('S2I-2023-032','khaled.turki@s2i.tn'),
  ('S2I-2023-032','bilel.nasri@s2i.tn'),('S2I-2023-032','nizar.benayed@s2i.tn'),
  ('S2I-2023-033','marouane.toumi@s2i.tn'),('S2I-2023-033','hamza.bouzid@s2i.tn'),
  ('S2I-2023-033','wassim.melki@s2i.tn'),('S2I-2023-033','sarra.ayari@s2i.tn'),
  ('S2I-2024-034','ahmed.benali@s2i.tn'),('S2I-2024-034','bilel.nasri@s2i.tn'),
  ('S2I-2024-034','asma.benromdhane@s2i.tn'),('S2I-2024-034','anis.louati@s2i.tn'),
  ('S2I-2024-035','rim.khalfallah@s2i.tn'),('S2I-2024-035','emna.gargouri@s2i.tn'),
  ('S2I-2025-036','firas.bensalah@s2i.tn'),('S2I-2025-036','mourad.ferchichi@s2i.tn'),
  ('S2I-2025-036','sabrine.chahed@s2i.tn'),('S2I-2025-036','chiraz.sfar@s2i.tn'),
  ('S2I-2023-037','khaled.turki@s2i.tn'),('S2I-2023-037','marouane.toumi@s2i.tn'),
  ('S2I-2023-037','hamza.bouzid@s2i.tn'),('S2I-2023-037','nizar.benayed@s2i.tn'),
  ('S2I-2024-038','olfa.benyoussef@s2i.tn'),('S2I-2024-038','leila.benfredj@s2i.tn'),
  ('S2I-2025-039','dorra.hammami@s2i.tn'),('S2I-2025-039','amira.jebali@s2i.tn'),
  ('S2I-2025-040','zied.benamor@s2i.tn'),('S2I-2025-040','sabrine.chahed@s2i.tn'),
  ('S2I-2022-041','bilel.nasri@s2i.tn'),('S2I-2022-041','anis.louati@s2i.tn'),
  ('S2I-2023-042','yassine.chebbi@s2i.tn'),('S2I-2023-042','rim.khalfallah@s2i.tn'),
  ('S2I-2024-043','wassim.melki@s2i.tn'),('S2I-2024-043','emna.gargouri@s2i.tn'),
  ('S2I-2025-044','nizar.benayed@s2i.tn'),('S2I-2025-044','firas.bensalah@s2i.tn'),
  ('S2I-2024-045','asma.benromdhane@s2i.tn'),('S2I-2024-045','hajer.sassi@s2i.tn'),
  ('S2I-2025-046','bilel.nasri@s2i.tn'),('S2I-2025-046','chiraz.sfar@s2i.tn'),
  ('S2I-2025-046','dorra.hammami@s2i.tn'),
  ('S2I-2025-047','leila.benfredj@s2i.tn'),('S2I-2025-047','zied.benamor@s2i.tn'),
  ('S2I-2023-048','ahmed.benali@s2i.tn'),('S2I-2023-048','yassine.chebbi@s2i.tn'),
  ('S2I-2023-048','bilel.nasri@s2i.tn'),('S2I-2023-048','hamza.bouzid@s2i.tn'),
  ('S2I-2024-049','marouane.toumi@s2i.tn'),('S2I-2024-049','salma.mansouri@s2i.tn'),
  ('S2I-2025-050','amira.jebali@s2i.tn'),('S2I-2025-050','sabrine.chahed@s2i.tn')
)
ON CONFLICT DO NOTHING;

-- ── §6  Billing milestones ────────────────────────────────────
-- (French original: "Jalons de facturation")
-- Pattern of 4 milestones: 20 % at kick-off · 40 % mid-way · 30 % at delivery ·
-- 10 % at the end of the warranty.
-- A milestone is one line of the invoicing plan of a contract: a share of the
-- total, a planned date, and a state (PREVU = planned, FACTURE = invoiced,
-- PAYE = paid). The KPI engine adds up the FACTURE and PAYE ones to compute
-- total_facture and then the FAE of V22.
--
-- Written as a loop because the same commercial pattern applies to all 50
-- projects; only the amounts and the dates change. Fifty hand-written sets of
-- four milestones would be 200 lines that could silently stop adding up to
-- 100 %.
DO $$
DECLARE
  p RECORD;       -- the project being processed
  b NUMERIC;      -- its reference budget
  d0 DATE; d_end DATE;   -- its start and end dates
  dur_months INT;        -- its duration in months
BEGIN
  -- ORDER BY code only makes the generated ids follow the project codes, which
  -- makes the result easier to read when checking the data by hand.
  FOR p IN SELECT id, code, initial_budget, revised_budget, start_date, end_date, status
           FROM projects WHERE deleted = FALSE ORDER BY code
  LOOP
    -- COALESCE returns its first argument that is not NULL.
    -- The reference budget is therefore "revised if there is one, otherwise the
    -- initial one, otherwise 100000". That last fallback is what stops the whole
    -- loop from writing NULL amounts on a project whose budget was never filled
    -- in — four milestones of NULL would make the invoicing screen unusable and
    -- the KPI totals empty.
    b     := COALESCE(p.revised_budget, p.initial_budget, 100000);
    d0    := COALESCE(p.start_date, '2023-01-01');
    d_end := COALESCE(p.end_date, d0 + INTERVAL '12 months');
    -- AGE(end, start) gives the gap as an interval such as "1 year 8 mons";
    -- EXTRACT(MONTH FROM ...) takes only the MONTHS part of it, so a project of
    -- 1 year and 8 months yields 8, not 20. ::INT is the PostgreSQL cast that
    -- turns the numeric result into a whole number.
    -- GREATEST(3, ...) keeps the value at three minimum. Why it matters: the
    -- mid-way milestone below divides this number by two, and a duration of 0 or
    -- 1 would place the second milestone on the very day of the first.
    dur_months := GREATEST(3, EXTRACT(MONTH FROM AGE(d_end, d0))::INT);

    -- Milestone 1: 20 % at kick-off.
    -- The two CASE expressions read the state of the project to decide whether
    -- this milestone has been invoiced. A project that has started (ACTIVE) or
    -- finished (COMPLETED) has certainly billed its kick-off, so the milestone
    -- is PAYE and gets an invoice date; a DRAFT project has not, so both stay
    -- NULL / PREVU. Without this, every demo project would show a fully invoiced
    -- plan, including the ones that have not started.
    INSERT INTO jalons_facturation(project_id, label, pourcentage, montant, date_prevue, date_facture, statut, created_at, updated_at, deleted)
    VALUES(p.id, 'Démarrage et installation', 20, round(b*0.20,2),
      d0 + INTERVAL '1 month',
      CASE WHEN p.status IN ('COMPLETED','ACTIVE') THEN d0 + INTERVAL '5 weeks' ELSE NULL END,
      CASE WHEN p.status IN ('COMPLETED','ACTIVE') THEN 'PAYE' ELSE 'PREVU' END,
      NOW(), NOW(), false);

    -- Milestone 2: 40 % mid-way.
    -- This one has three states instead of two: COMPLETED means it was paid,
    -- ACTIVE means it was invoiced but not paid yet (FACTURE) — and only for a
    -- project long enough to have reached its middle, hence "dur_months > 8".
    -- That FACTURE state is what gives the KPI screens a non-zero FAE (revenue
    -- earned and invoiced but not yet cashed in); with only PAYE and PREVU the
    -- indicator would always be empty and could not be shown.
    INSERT INTO jalons_facturation(project_id, label, pourcentage, montant, date_prevue, date_facture, statut, created_at, updated_at, deleted)
    VALUES(p.id, 'Livraison intermédiaire', 40, round(b*0.40,2),
      -- Building an interval from a number: || concatenates, so 18/2 becomes the
      -- text '9 months', and ::INTERVAL turns that text into a real interval.
      -- Why the detour: PostgreSQL has no "INTERVAL n months" syntax taking a
      -- variable — INTERVAL only accepts a literal.
      -- Note dur_months is an INT, so the division is a WHOLE division: 9/2 = 4,
      -- not 4.5. Half a month of drift on a demo date, and it keeps the value a
      -- whole number of months.
      d0 + (dur_months/2 || ' months')::INTERVAL,
      CASE WHEN p.status = 'COMPLETED' THEN d0 + ((dur_months/2)+1 || ' months')::INTERVAL
           WHEN p.status = 'ACTIVE' AND dur_months > 8 THEN d0 + ((dur_months/2)+1 || ' months')::INTERVAL
           ELSE NULL END,
      CASE WHEN p.status = 'COMPLETED' THEN 'PAYE'
           WHEN p.status = 'ACTIVE' AND dur_months > 8 THEN 'FACTURE'
           ELSE 'PREVU' END,
      NOW(), NOW(), false);

    -- Milestone 3: 30 % at delivery. Dated backwards from the end of the project
    -- (d_end − 1 month), because delivery is tied to the end date, not to the
    -- start. Only a COMPLETED project can have reached it.
    INSERT INTO jalons_facturation(project_id, label, pourcentage, montant, date_prevue, date_facture, statut, created_at, updated_at, deleted)
    VALUES(p.id, 'Recette et déploiement', 30, round(b*0.30,2),
      d_end - INTERVAL '1 month',
      CASE WHEN p.status = 'COMPLETED' THEN d_end - INTERVAL '2 weeks' ELSE NULL END,
      CASE WHEN p.status = 'COMPLETED' THEN 'PAYE' ELSE 'PREVU' END,
      NOW(), NOW(), false);

    -- Milestone 4: 10 % at the end of the warranty, three months AFTER the end
    -- of the project. This is the share the client keeps back until the warranty
    -- period runs out.
    -- 20 + 40 + 30 + 10 = 100: the four shares add up to the whole contract.
    -- Its invoice date falls three months after the project ends, that is AFTER
    -- the snapshot date §10 uses for a finished project (the end date itself).
    -- That is why §10 records 90 % invoiced and not 100 %: at the moment the
    -- snapshot describes, this last milestone had not been billed yet.
    INSERT INTO jalons_facturation(project_id, label, pourcentage, montant, date_prevue, date_facture, statut, created_at, updated_at, deleted)
    VALUES(p.id, 'Fin de garantie', 10, round(b*0.10,2),
      d_end + INTERVAL '3 months',
      CASE WHEN p.status = 'COMPLETED' THEN d_end + INTERVAL '3 months' + INTERVAL '2 weeks' ELSE NULL END,
      CASE WHEN p.status = 'COMPLETED' THEN 'PAYE' ELSE 'PREVU' END,
      NOW(), NOW(), false);
  END LOOP;
END $$;

-- ── §7  Payments (milestones marked PAYE) ─────────────────────
-- (French original: "Paiements (jalons PAYE)")
-- One payment row for each milestone §6 marked as paid. The two must agree:
-- a milestone flagged PAYE with no payment behind it would make the invoicing
-- follow-up show money received that nobody can trace.
-- The rows are derived from the milestones instead of being written by hand,
-- which is what guarantees they always agree.
INSERT INTO paiements (jalon_id, montant_recu, date_paiement, reference, created_at, updated_at, deleted)
-- Paid in full (montant_recu = the whole milestone) and cashed in fifteen days
-- after the invoice, a normal payment delay.
SELECT jf.id, jf.montant, jf.date_facture + INTERVAL '15 days',
  -- A believable bank reference built from the data itself: 'VIR-' (virement, a
  -- bank transfer), the invoice date as YYYYMMDD, then the project id. to_char
  -- formats a date into text with that pattern, and || glues the pieces.
  -- Built rather than random so the same run always produces the same reference,
  -- and so a reference read on screen can be traced back to its project.
  'VIR-' || to_char(jf.date_facture, 'YYYYMMDD') || '-' || jf.project_id,
  NOW(), NOW(), false
FROM jalons_facturation jf
-- The "date_facture IS NOT NULL" guard is the important one: the invoice date is
-- used twice above, in the payment date and in the reference. In SQL any
-- arithmetic or concatenation with NULL gives NULL, so without this filter a
-- paid milestone with no invoice date would produce a payment with no date and a
-- reference reading simply nothing.
WHERE jf.statut = 'PAYE' AND jf.date_facture IS NOT NULL AND jf.deleted = FALSE
ON CONFLICT DO NOTHING;

-- ── §8  Contract amendments ("avenants") ──────────────────────
-- An amendment is a signed change to the contract: it adds money and man-days.
-- Only eleven projects get one, and four of those get a second: an amendment on
-- every project would be unrealistic, and it would hide the difference the
-- screens are meant to show, between initial_budget and revised_budget.
-- Note what this section does NOT do: it does not touch projects.revised_budget.
-- The revised amounts were written directly in §4, so the two are consistent
-- only because they were written to be. Changing one without the other would
-- make the amendment list disagree with the project sheet.
INSERT INTO avenants (project_id, numero, objet, montant, workload_days, date_avenant, created_at, updated_at, deleted)
-- The number is built from the project code ('AV-S2I-2022-001-001'), so it is
-- unique and readable without looking anything up. round(..., 0) keeps the added
-- man-days a whole number of days.
SELECT p.id, 'AV-' || p.code || '-001',
  'Extension du périmètre fonctionnel suite à la demande du client',
  round(p.initial_budget * 0.06, 2), round(p.sold_workload_days * 0.05, 0),
  p.start_date + INTERVAL '6 months', NOW(), NOW(), false
FROM projects p
WHERE p.code IN ('S2I-2022-001','S2I-2022-002','S2I-2023-003','S2I-2023-004',
                 'S2I-2024-011','S2I-2022-021','S2I-2022-027','S2I-2023-028',
                 'S2I-2023-032','S2I-2023-037','S2I-2023-048')
  AND p.deleted = FALSE
ON CONFLICT DO NOTHING;

-- A second amendment, for four of those projects only. It is smaller than the
-- first (4 % instead of 6 %) and dated four months later, so a project sheet
-- shows a plausible history rather than two identical rows.
INSERT INTO avenants (project_id, numero, objet, montant, workload_days, date_avenant, created_at, updated_at, deleted)
SELECT p.id, 'AV-' || p.code || '-002',
  'Intégration d''un module complémentaire non prévu au contrat initial',
  round(p.initial_budget * 0.04, 2), round(p.sold_workload_days * 0.03, 0),
  p.start_date + INTERVAL '10 months', NOW(), NOW(), false
FROM projects p
WHERE p.code IN ('S2I-2022-001','S2I-2022-027','S2I-2023-028','S2I-2023-048')
  AND p.deleted = FALSE
ON CONFLICT DO NOTHING;

-- ── §9  Charges planifiées + réelles  (DO $$ — boucle) ────────
-- (French original: "Charges planifiées + réelles (DO $$ — loop)")
-- Planned workload and real workload, month by month, for every developer of
-- every project.
--   plan_charges    = the days the project manager PLANNED that person would
--                     spend on the project during that month.
--   charges_reelles = the days that person actually REPORTED, and who validated
--                     them.
-- Almost every figure in the application compares the two: the consumed
-- man-days of the KPI screens, the "reste à faire" (work remaining) of V22 and
-- the drift in days all come from this pair of tables. Leave them empty and all
-- of those indicators read zero on all 50 projects.
--
-- Written as three nested loops rather than as literal rows because the volume
-- is the point: 50 projects × their developers × up to four years of months is
-- several thousand rows. That is what makes the charts, the filters and the
-- pagination of the workload module worth showing at all.
--
-- DO $$ ... $$ opens an anonymous block of PL/pgSQL, the procedural language of
-- PostgreSQL. Plain SQL has no variables, no IF and no WHILE, and this section
-- needs all three. The $$ pair only quotes the body, so the apostrophes inside
-- it need no escaping.
DO $$
DECLARE
  -- RECORD = a row variable whose shape is decided by the query that fills it.
  -- Used here because the two FOR loops below select different column lists, and
  -- no single named type would fit both.
  proj  RECORD;    -- the project being processed
  mbr   RECORD;    -- one developer of that project
  m     DATE;      -- the month being processed, always the 1st of the month
  p_days NUMERIC;  -- days planned for that person, that month
  a_days NUMERIC;  -- days really reported for that person, that month
  v_by  BIGINT;    -- fallback validator, see just below
BEGIN
  -- Looked up ONCE, before the loops, and not inside them. Why: it is a
  -- constant, and running it inside would repeat the same query several thousand
  -- times.
  -- What it is for: charges_reelles.validated_by says WHO signed off the time
  -- sheet. It is used below as the fallback for a project that has no manager.
  -- The column is nullable, so NULL would be accepted by the database — but a
  -- row carrying a validation date with no validator is a time sheet validated
  -- by nobody, and that is exactly the hole an auditor looks for.
  SELECT id INTO v_by FROM users WHERE email = 'momo-chef@pms.local';

  -- Outer loop: every project, DRAFT ones included. A project being prepared
  -- can already have a team and a plan, and that is what a draft looks like; it
  -- simply gets no REAL charges, because the inner IF below only writes those
  -- for months that have passed.
  FOR proj IN
    SELECT p.id, p.start_date, p.end_date, p.status, p.code, p.chef_projet_id
    FROM projects p WHERE p.deleted = FALSE
  LOOP
    -- Middle loop: the DEVELOPPEUR members of that project, and only them.
    -- The project manager is skipped on purpose: §5 also writes a CHEF_PROJET
    -- row into team_assignments for every project, and counting him here would
    -- add one more full-time person to all 50 projects. Every consumed-days
    -- figure, every margin and every EVM indicator in the application would then
    -- be inflated by that phantom workload.
    FOR mbr IN
      SELECT ta.user_id
      FROM team_assignments ta
      WHERE ta.project_id = proj.id AND ta.deleted = FALSE
        AND ta.role_in_team = 'DEVELOPPEUR'
    LOOP
      -- date_trunc('month', d) moves a date back to the 1st of its own month.
      -- The "period" column is a MONTH KEY, so every row describing a given
      -- month must carry the exact same date. Without this, a project starting
      -- on the 17th would store period = the 17th; the partial unique index
      -- uk_pc_active (project_id, user_id, period) from V7 would then fail to
      -- see a second entry for that same month as a duplicate, and the monthly
      -- totals of the workload screen would count the month twice.
      m := date_trunc('month', proj.start_date);
      -- Inner loop: one turn per month of the project.
      -- LEAST returns the smallest of its arguments, COALESCE the first one that
      -- is not NULL. So the loop stops at the end of the project, or on
      -- 2026-08-01, whichever comes first.
      -- That second bound is the hard horizon of the demo data. Without it, a
      -- project with a distant end date — or with none at all — would keep
      -- generating months for years into the future and fill the two tables with
      -- rows no screen ever shows.
      -- Note the COALESCE is belt and braces here: PostgreSQL's LEAST already
      -- ignores a NULL argument, and 2026-08-01 is in practice always the
      -- smaller of the two, so it is what really ends the loop.
      WHILE m < LEAST(COALESCE(proj.end_date, '2026-12-01'::DATE), '2026-08-01'::DATE) LOOP
        -- Planifié : entre 14 et 21 jours (avec variance naturelle)
        -- Planned: between 14 and 21 days, with natural variance.
        -- random() returns a fraction between 0 and 1, so floor(random() * 8) is
        -- a whole number from 0 to 7, and p_days lands between 14 and 21.
        -- Why not one fixed number: an identical plan on every row would draw
        -- every chart of the workload module as a flat line, and the screens
        -- would demonstrate nothing.
        -- Why this range: V7 puts CHECK (planned_days > 0 AND planned_days <= 31)
        -- on the column, and 14 to 21 days is a believable share of a working
        -- month for somebody who is not on the project full time.
        p_days := 14 + (floor(random() * 8))::NUMERIC;

        -- ON CONFLICT DO NOTHING with no target covers every unique index of the
        -- table, which here means uk_pc_active (project_id, user_id, period
        -- WHERE deleted = FALSE) from V7.
        -- Why it is needed: it makes the section safe to replay. The §-1
        -- clean-up at the top of this file normally empties the demo rows first,
        -- but if this file were played by hand on a database where that did not
        -- happen, the very first month would abort the whole migration on a
        -- duplicate key instead of being quietly skipped.
        INSERT INTO plan_charges(project_id, user_id, period, planned_days, created_at, updated_at, deleted)
        VALUES(proj.id, mbr.user_id, m, p_days, NOW(), NOW(), false)
        ON CONFLICT DO NOTHING;

        -- Réel : pour les mois passés uniquement (< 2026-07-01)
        -- Real charges: for past months only (before 2026-07-01).
        -- The planned loop above runs up to July 2026, this one stops at June,
        -- so the newest month of the demo is PLANNED but not yet REPORTED.
        -- That one-month gap is deliberate: it is what a month in progress looks
        -- like, and it is what lets the workload screen be shown with a month
        -- still waiting for input rather than a suspiciously complete history.
        IF m < '2026-07-01'::DATE THEN
          -- The real figure is the planned one shifted by −3 to +3 days:
          -- floor(random() * 7) gives 0 to 6, and the −3 moves that to −3 to +3.
          -- Why derive it from p_days instead of drawing a fresh number: the two
          -- have to stay close to each other. Drawn independently, a plan of 20
          -- days could meet a reality of 3, and every project in the portfolio
          -- would look catastrophically off track on the KPI screens.
          a_days := p_days + (-3 + floor(random() * 7))::NUMERIC;
          -- Squeeze the result between 0 and 22. GREATEST keeps the larger of
          -- its arguments, LEAST the smaller, so the pair clamps both ends.
          -- Why 0: a negative number of days worked is meaningless, and V7 has
          -- CHECK (actual_days >= 0 AND actual_days <= 31) — a −1 would abort
          -- the migration.
          -- Why 22: that is roughly the number of working days in a month, so
          -- nobody in the demo reports more days than the month actually holds.
          a_days := GREATEST(0, LEAST(a_days, 22));

          -- submitted_at and validated_at are set to the 1st of the month plus
          -- 23 and 27 days: the time sheet is handed in near the end of the
          -- month and signed off a few days later.
          -- Why they are filled at all: a row with no validation date is still a
          -- draft, and the KPI engine only counts VALIDATED workload. Left NULL,
          -- every consumed-days figure in the application would be zero even
          -- though the rows exist.
          -- validated_by uses COALESCE, so it is the project's own manager when
          -- there is one, and the account read once at the top of the block
          -- otherwise — never NULL beside a validation date.
          INSERT INTO charges_reelles(
            project_id, user_id, period, actual_days,
            submitted_at, validated_at, validated_by,
            created_at, updated_at, deleted)
          VALUES(
            proj.id, mbr.user_id, m, a_days,
            m + INTERVAL '23 days',
            m + INTERVAL '27 days',
            COALESCE(proj.chef_projet_id, v_by),
            NOW(), NOW(), false)
          ON CONFLICT DO NOTHING;
        END IF;

        -- Step to the next month. m is already the 1st of a month, so adding
        -- one month always lands on the 1st of the next one and the period key
        -- stays clean. This line is also what ends the WHILE loop: remove it and
        -- the migration never finishes.
        m := m + INTERVAL '1 month';
      END LOOP;
    END LOOP;
  END LOOP;
END $$;

-- ── §10  Snapshots KPI ────────────────────────────────────────
-- (French original: "Snapshots KPI")
-- One row of snapshot_kpis = the monthly project review of ONE project at ONE
-- date: the "Situation actuelle" block started by V8 and completed by V22.
--
-- A snapshot is a PHOTOGRAPH, not a live calculation. The figures are frozen as
-- they stood on snapshot_date and are never recomputed afterwards. That is the
-- whole point of the table: it is what gives the review screens a month-by-month
-- history instead of one always-current number that erases its own past.
--
-- This block writes that history for the demo, because no amount of clicking can
-- create a past. Without it the trend charts of the KPI module would hold a
-- single point, and the comparison of one review against the previous one —
-- the reason F-AFF-13 asks for the module — could not be shown at all.
--
-- Three shapes, chosen by the project status:
--   COMPLETED : ONE final snapshot, dated at the end of the project.
--   ACTIVE    : THREE monthly snapshots, so a trend can be drawn.
--   DRAFT     : none. There is nothing to measure on a project that has not
--               started, and a review full of zeros would read as a failing
--               project rather than an unstarted one. That is why the block
--               below is IF / ELSIF with no ELSE.
DO $$
DECLARE
  p       RECORD;   -- the project being processed
  b       NUMERIC;  -- its reference budget, in TND
  ev      NUMERIC;  -- Earned Value progress in percent (V22: ev_pct)
  consumed NUMERIC; -- money really spent so far
  margin  NUMERIC;  -- value earned − money spent
  snap_d  DATE;     -- the date the snapshot describes
  total_f NUMERIC;  -- amount already invoiced at that date
  ca_prod NUMERIC;  -- production revenue = budget × progress
BEGIN
  -- Note two columns are selected and never used in the body below:
  -- pr.start_date and pr.marge_nette_vendue. They cost nothing and break
  -- nothing; the sold margin in particular is the V22 baseline the review
  -- compares against, and it is read by the application, not by this seed.
  FOR p IN SELECT pr.id, pr.status, pr.initial_budget, pr.revised_budget,
                  pr.start_date, pr.end_date, pr.marge_nette_vendue, pr.sold_workload_days
           FROM projects pr WHERE pr.deleted = FALSE
  LOOP
    -- Reference budget: the revised amount if an amendment changed it, else the
    -- original, else 100000. This is deliberately the SAME rule as §6 uses for
    -- the billing milestones. If the two disagreed, the invoiced share computed
    -- below would no longer match the invoicing plan, and the two screens would
    -- contradict each other in front of the jury.
    b := COALESCE(p.revised_budget, p.initial_budget, 100000);

    IF p.status = 'COMPLETED' THEN
      -- The final review of a finished project is dated on its end date.
      -- COALESCE supplies a date for the rare project stored without one;
      -- snapshot_date is NOT NULL in V8, so a NULL here would stop the migration.
      snap_d   := COALESCE(p.end_date, '2024-12-31');
      -- 98 to 100 % done: floor(random() * 3) gives 0, 1 or 2.
      -- Not a flat 100 on purpose. A finished project can still carry a last
      -- reserve, and a column of identical 100s reads as a constant somebody
      -- typed, not as data the system measured.
      ev       := 98 + floor(random() * 3);            -- 98-100%
      -- Money really spent: 87 % to 105 % of the budget. The range crosses 100
      -- deliberately, so the demo portfolio holds both projects that made money
      -- and projects that overran. A portfolio where every project is profitable
      -- would prove nothing about the margin screens.
      consumed := b * (0.87 + random() * 0.18);
      -- Production revenue: budget × progress. This is the earned value of EVM
      -- expressed in money — the same formula V22 documents for ca_production.
      ca_prod  := b * ev / 100;
      -- 90 % invoiced, not 100, and the reason is in §6: the last milestone
      -- (10 %, end of warranty) is dated THREE MONTHS AFTER the project ends,
      -- while this snapshot is dated ON the end date. At the moment this review
      -- describes, only the first three milestones (20 + 40 + 30) had been
      -- billed. The gap is what makes the FAE indicator below non-zero.
      total_f  := b * 0.90;                            -- facturé 90% (garantie à venir)
      -- Margin = value earned − money spent.
      margin   := ca_prod - consumed;

      -- Reading the VALUES list below, the parts that are not obvious:
      --  * eac = budget_consome. EAC means "Estimate At Completion", the
      --    forecast of the final cost. On a project that is OVER, the forecast
      --    IS the final cost. Writing the budget there instead would quietly
      --    hide every overrun in the portfolio.
      --  * delivery_pct = 100 and raf_jh = 0: everything planned was handed over
      --    and nothing is left to do. Both are literal here because "finished"
      --    is not a matter of chance.
      --  * round(x, 2) for money, round(x, 4) for ratios — the exact precisions
      --    V22 gave those columns. Rounding a ratio to 2 decimals would move a
      --    million-dinar margin by thousands once the screen multiplies it back.
      --  * NULLIF(ca_prod, 0) returns NULL when ca_prod is zero, so the division
      --    yields NULL instead of raising "division by zero". That matters far
      --    more than one empty cell: a division by zero here aborts the entire
      --    migration and leaves the database half seeded.
      --  * COALESCE(p.sold_workload_days, 600) supplies man-days for a project
      --    whose sold workload was never filled in; without it the man-days
      --    block of the review would be empty on exactly those projects.
      --  * fae = ca_prod − total_f: revenue earned but not yet invoiced, the
      --    10 % of warranty still to bill.
      -- And note what is NOT written: derive_jh and date_fin_estimee stay NULL.
      -- On a finished project the drift in days and the forecast end date have
      -- no meaning left, and V22 made every column nullable precisely so that
      -- "not measured" could be told apart from "measured, and it is zero".
      INSERT INTO snapshot_kpis(project_id, snapshot_date, budget_planifie, budget_consome,
        eac, marge, taux_consommation, ev_pct, delivery_pct,
        consomme_jh, raf_jh, ca_production, total_facture, fae, marge_actuelle, marge_actuelle_pct,
        faits_marquants, created_at, updated_at, deleted)
      VALUES(p.id, snap_d, b, round(consumed,2),
        round(consumed,2), round(margin,2), round(consumed/b,4),
        ev, 100,
        round(COALESCE(p.sold_workload_days,600)*ev/100, 0),
        0,
        round(ca_prod,2), round(total_f,2), round(ca_prod-total_f,2),
        round(margin,2), round(margin/NULLIF(ca_prod,0),4),
        'Projet clôturé avec succès. Recette client signée. Satisfaction globale : Bonne.',
        NOW(), NOW(), false)
      ON CONFLICT DO NOTHING;

    ELSIF p.status = 'ACTIVE' THEN
      -- Snapshot mensuel sur 3 derniers mois
      -- A monthly snapshot at each of the last three month ends.
      -- FOREACH ... IN ARRAY walks the values of an array one at a time.
      -- Three FIXED dates rather than a window computed from the current date,
      -- because the demo has to look the same whenever it is played; a moving
      -- window would show a different picture every month and a screenshot in
      -- the report would stop matching the running application.
      -- Three points and not one: two are the minimum to draw a line, three make
      -- a trend readable on the chart.
      FOREACH snap_d IN ARRAY ARRAY['2026-04-30'::DATE,'2026-05-31'::DATE,'2026-06-30'::DATE]
      LOOP
        -- 40 to 84 % done: projects in mid-flight.
        -- Be aware of what this does NOT guarantee: the value is drawn afresh on
        -- every turn, so the three snapshots of one project are independent and
        -- the progress of a project can come out LOWER in May than in April.
        ev       := 40 + floor(random() * 45);         -- 40-84%
        -- Money spent: 90 % to 110 % of the value earned, so some projects run
        -- at a profit and some at a loss at any given month.
        consumed := b * ev / 100 * (0.90 + random() * 0.20);
        ca_prod  := b * ev / 100;
        -- Invoiced: 80 % of the progress, capped at 70 % of the contract by
        -- LEAST, which keeps the smaller of its two arguments.
        -- Why invoicing lags behind progress: a client is billed at milestones,
        -- not continuously. The gap between ca_production and total_facture is
        -- exactly the FAE of V22 — revenue earned but not yet billed. If
        -- invoicing tracked progress one for one, that indicator would always be
        -- zero and the screen built for it could never be demonstrated.
        -- Why a cap: it stops an active project from appearing almost fully
        -- invoiced. Note it is only close to the invoicing plan, not computed
        -- from it: §6 bills a running project at most 60 % (the 20 % kick-off
        -- plus the 40 % mid-way milestone), not 70 %.
        total_f  := b * LEAST(ev/100 * 0.80, 0.70);
        margin   := ca_prod - consumed;

        -- Reading the VALUES list below, the parts that are not obvious:
        --  * eac is NOT the money already spent this time. It is the budget
        --    moved by ±5 %: random() − 0.5 gives −0.5 to +0.5, times 0.10 gives
        --    −5 % to +5 %. The project is still running, so EAC is a GUESS about
        --    where it will land, and that guess is precisely what a monthly
        --    review argues about. ::NUMERIC is required because random() returns
        --    a floating-point value and round(x, 2) only exists for NUMERIC.
        --  * delivery_pct = ev + floor(random()*5 - 2), a drift of −2 to +2
        --    points around the progress. Why they must differ: V22 keeps the two
        --    side by side exactly so a review can ask why they disagree. Two
        --    identical columns would make that question impossible to pose.
        --  * consomme_jh and raf_jh split the sold workload by the progress; the
        --    remaining part is multiplied by 0.90, so the work left is estimated
        --    a little cheaper than it was sold — what a team that has learned the
        --    project actually reports.
        --  * NULLIF(ca_prod, 0) again guards the division, for the same reason as
        --    in the COMPLETED branch: a division by zero aborts the migration.
        INSERT INTO snapshot_kpis(project_id, snapshot_date, budget_planifie, budget_consome,
          eac, marge, taux_consommation, ev_pct, delivery_pct,
          consomme_jh, raf_jh, ca_production, total_facture, fae, marge_actuelle, marge_actuelle_pct,
          faits_marquants, created_at, updated_at, deleted)
        VALUES(p.id, snap_d, b, round(consumed,2),
          round((b*(1.0 + (random()-0.5)*0.10))::NUMERIC, 2),
          round(margin,2), round(consumed/b,4),
          ev, ev + floor(random()*5 - 2),
          round(COALESCE(p.sold_workload_days,500)*ev/100, 0),
          round(COALESCE(p.sold_workload_days,500)*(1-ev/100)*0.90, 0),
          round(ca_prod,2), round(total_f,2), round(ca_prod-total_f,2),
          round(margin,2), round(margin/NULLIF(ca_prod,0),4),
          'Avancement conforme au planning. Prochaine livraison dans les délais.',
          NOW(), NOW(), false)
        ON CONFLICT DO NOTHING;
      END LOOP;
    END IF;
  END LOOP;
END $$;

-- ── §11  Missions ─────────────────────────────────────────────
-- (French original: "Missions")
-- A mission is a trip made for a project: a kick-off meeting, a workshop at the
-- client's office, a steering committee. Table created by V10; the cost of a
-- trip is stored as "composantes" (components) further down — a plane ticket, a
-- per diem, and so on.
--
-- WHY EVERY ROW IS DERIVED FROM THE PROJECTS
-- Each statement is "INSERT INTO ... SELECT ... FROM projects", never a list of
-- literal rows. The dates are computed from each project's own start date, so a
-- mission can never land outside the project it belongs to, and 50 projects are
-- covered by three statements instead of 150 hand-written lines that would drift
-- apart the first time a project date changed.
--
-- A HONEST WORD ABOUT "ON CONFLICT DO NOTHING" IN §11 TO §15
-- On these tables the clause catches nothing. missions, composantes_mission,
-- risks, livrables, parties_prenantes and demandes_changement have no unique key
-- beyond their primary key, and that key is a BIGSERIAL counter which never
-- collides. So replaying this file WOULD duplicate these rows; the clause is
-- only a habit carried over from the sections above, where real unique indexes
-- exist. What actually makes the file safe to replay is the §-1 clean-up block
-- at the top, which deletes the demo rows before anything is written.

-- Mission 1: the kick-off workshop, one week after the project starts, running
-- three days — the 8th to the 10th, both ends counted.
-- "lieu" (the place) is set to the client's name: the team travels to the
-- client's site. lieu is nullable in V10, so a project with no client simply
-- records a trip with no location instead of failing.
-- "status != 'DRAFT'" is the filter that matters: a project that has not started
-- has held no kick-off, and a draft project carrying a past meeting would
-- contradict its own status on the governance screen.
-- The doubled apostrophe in 'l''équipe' is how SQL escapes a single quote inside
-- a single-quoted string; one quote alone would close the string early and turn
-- the rest of the line into a syntax error.
INSERT INTO missions(project_id, user_id, objet, lieu, date_debut, date_fin, created_at, updated_at, deleted)
SELECT p.id, p.chef_projet_id,
  'Réunion de cadrage et atelier de lancement avec l''équipe client',
  p.client,
  p.start_date + INTERVAL '1 week',
  p.start_date + INTERVAL '1 week' + INTERVAL '2 days',
  NOW(), NOW(), false
FROM projects p WHERE p.deleted = FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- Mission 2: the specification validation workshop, two months in, over two
-- days. Restricted to COMPLETED projects: only a project that went all the way
-- certainly held this workshop. This is also what gives finished projects a
-- richer travel history than running ones, so the mission list is not the same
-- on every project.
INSERT INTO missions(project_id, user_id, objet, lieu, date_debut, date_fin, created_at, updated_at, deleted)
SELECT p.id, p.chef_projet_id,
  'Atelier de validation des spécifications fonctionnelles',
  p.client,
  p.start_date + INTERVAL '2 months',
  p.start_date + INTERVAL '2 months' + INTERVAL '1 day',
  NOW(), NOW(), false
FROM projects p WHERE p.deleted = FALSE AND p.status = 'COMPLETED'
ON CONFLICT DO NOTHING;

-- Mission 3: the mid-project steering committee, five months in.
-- date_debut and date_fin are the SAME day: a one-day trip. V10 has
-- CHECK (date_fin >= date_debut), so equal dates are legal — this is not a
-- mistake, and the per diem statement below treats it specially.
-- The explicit list of twelve project codes is what stops the demo looking
-- machine-made: without it all 50 projects would carry the exact same three
-- missions and the screens would show an obviously synthetic pattern.
-- The list matches on the CODE and not on the id, because ids come from a
-- BIGSERIAL counter and differ from one database to the next; hard-coded ids
-- would point at random projects on a freshly created database.
INSERT INTO missions(project_id, user_id, objet, lieu, date_debut, date_fin, created_at, updated_at, deleted)
SELECT p.id, p.chef_projet_id,
  'Présentation du comité de pilotage mi-parcours',
  p.client,
  p.start_date + INTERVAL '5 months',
  p.start_date + INTERVAL '5 months',
  NOW(), NOW(), false
FROM projects p WHERE p.deleted = FALSE AND p.status IN ('COMPLETED','ACTIVE')
  AND p.code IN ('S2I-2022-001','S2I-2022-002','S2I-2023-003','S2I-2023-004',
                 'S2I-2024-006','S2I-2024-007','S2I-2024-008','S2I-2024-010',
                 'S2I-2024-011','S2I-2022-021','S2I-2022-027','S2I-2023-028')
ON CONFLICT DO NOTHING;

-- Composantes des missions
-- Mission components: what a trip costs, broken down line by line. V10 allows
-- PERDIEM, BILLET, TIMBRE, TRANSPORT and SEJOUR; the demo uses the two that
-- appear on every real expense claim, so the mission screen has something to
-- total up.

-- A flat 180 TND plane ticket on EVERY mission, one-day trips included.
-- Note the FROM clause: "FROM missions m", not FROM projects. A component hangs
-- on a TRIP, not on a project, so the rows are derived from the missions written
-- just above. This also means the statement must stay after them — run first, it
-- would find nothing to attach to and insert nothing at all.
INSERT INTO composantes_mission(mission_id, type_composante, montant, devise, description, created_at, updated_at, deleted)
SELECT m.id, 'BILLET', 180.00, 'TND', 'Billet d''avion aller-retour Tunis–destination', NOW(), NOW(), false
FROM missions m WHERE m.deleted = FALSE
ON CONFLICT DO NOTHING;

-- The per diem (the fixed daily allowance paid to somebody travelling) is
-- 85 TND times the number of days.
-- "m.date_fin - m.date_debut": subtracting two DATE values in PostgreSQL gives a
-- whole number of DAYS, not an interval. The "+ 1" counts both ends, so a trip
-- from the 8th to the 10th is 3 days and not 2 — without it the company would
-- underpay every traveller by exactly one day.
-- The very same expression is repeated inside the description text, glued with
-- || (which joins text), so the wording and the amount can never disagree.
-- "WHERE ... date_fin > date_debut" leaves out the one-day missions of statement
-- 3, which therefore get a ticket and no per diem. Worth knowing: this is a
-- choice about the demo data, NOT something the schema forces. V10 only requires
-- montant > 0, and 85 × 1 day would satisfy that perfectly well.
INSERT INTO composantes_mission(mission_id, type_composante, montant, devise, description, created_at, updated_at, deleted)
SELECT m.id, 'PERDIEM', 85.00 * (m.date_fin - m.date_debut + 1), 'TND',
  'Per diem journalier × ' || (m.date_fin - m.date_debut + 1) || ' jour(s)',
  NOW(), NOW(), false
FROM missions m WHERE m.deleted = FALSE AND m.date_fin > m.date_debut
ON CONFLICT DO NOTHING;

-- ── §12  Risques ──────────────────────────────────────────────
-- (French original: "Risques")
-- The risk register of each project: what could go wrong, how likely it is, how
-- bad it would be, and what is planned about it.
-- The table comes from V11, which restricts probabilite and impact to
-- FAIBLE / MOYEN / ELEVE and statut to OUVERT / MITIGE / FERME. Every value
-- written below is one of those; anything else and the CHECK constraints would
-- reject the row and stop the whole migration.
--
-- Two risks go to every started project, two more only to a chosen list, so the
-- registers differ from one project to another instead of being 50 copies.

-- Risk 1: client environments delivered late. On every started project, because
-- it is the one delay every integrator meets.
-- The CASE ties the state of the RISK to the state of the PROJECT: a finished
-- project has closed its risks (FERME), a running one still carries them
-- (OUVERT). Without it every project, finished or not, would show an open
-- register, and the governance screen could never be shown with a closed risk in
-- it.
INSERT INTO risks(project_id, description, probabilite, impact, plan_mitigation, statut, created_at, updated_at, deleted)
SELECT p.id,
  'Retard de disponibilité des environnements client (recette, intégration)',
  'MOYEN','ELEVE',
  'Prévoir des environnements propres hébergés par S2I et procéder à la migration en fin de phase.',
  CASE WHEN p.status='COMPLETED' THEN 'FERME' ELSE 'OUVERT' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- Risk 2: the scope was never firmly agreed — the classic cause of workload
-- drift, and the business reason the avenants of §8 exist at all.
-- Note this CASE ends on MITIGE, not FERME: on a finished project this risk was
-- CONTAINED, not eliminated. Using the third state deliberately means the demo
-- exercises all three values the CHECK allows, so the status filter of the risk
-- screen can be shown actually filtering something.
INSERT INTO risks(project_id, description, probabilite, impact, plan_mitigation, statut, created_at, updated_at, deleted)
SELECT p.id,
  'Périmètre fonctionnel insuffisamment défini — risque de dérive des charges',
  'MOYEN','MOYEN',
  'Geler le périmètre en phase de cadrage. Tout ajout passe par un avenant signé.',
  CASE WHEN p.status='COMPLETED' THEN 'MITIGE' ELSE 'OUVERT' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- Risk 3: key people leaving mid-project. ACTIVE projects only, and only eight
-- of them: a risk that sits on every project is not a risk, it is a property of
-- the company, and a register where every line is identical teaches the reader
-- nothing.
-- Its statut is the literal 'OUVERT' rather than a CASE, because the WHERE
-- already keeps ACTIVE projects only — there is no finished project here whose
-- state would need deciding.
INSERT INTO risks(project_id, description, probabilite, impact, plan_mitigation, statut, created_at, updated_at, deleted)
SELECT p.id,
  'Turnover des ressources clés en cours de projet',
  'FAIBLE','ELEVE',
  'Maintien d''une documentation technique à jour (wiki projet). Plan de succession identifié.',
  'OUVERT',
  NOW(), NOW(), false
FROM projects p
WHERE p.deleted=FALSE AND p.status='ACTIVE'
  AND p.code IN ('S2I-2023-004','S2I-2024-006','S2I-2024-007','S2I-2024-011',
                 'S2I-2023-022','S2I-2022-027','S2I-2023-028','S2I-2023-048')
ON CONFLICT DO NOTHING;

-- Risk 4: integration with the client's legacy systems. The only one rated
-- ELEVE / ELEVE, on six projects.
-- High probability AND high impact is the top-right corner of the risk matrix,
-- so this statement is what makes the heat map of the governance screen show
-- something other than a single flat colour.
INSERT INTO risks(project_id, description, probabilite, impact, plan_mitigation, statut, created_at, updated_at, deleted)
SELECT p.id,
  'Intégration complexe avec les systèmes legacy du client',
  'ELEVE','ELEVE',
  'Phase de POC technique dédiée. Contrat de niveau de service avec la DSI client.',
  'OUVERT',
  NOW(), NOW(), false
FROM projects p
WHERE p.deleted=FALSE AND p.status='ACTIVE'
  AND p.code IN ('S2I-2022-001','S2I-2022-002','S2I-2022-027','S2I-2023-028',
                 'S2I-2024-011','S2I-2023-022')
ON CONFLICT DO NOTHING;

-- ── §13  Livrables ────────────────────────────────────────────
-- (French original: "Livrables")
-- The deliverables of each project: the documents and versions the client is
-- owed, each with a due date and a state. V11 allows EN_ATTENTE, EN_COURS,
-- LIVRE and VALIDE only.
-- These rows matter beyond the governance screen: delivery_pct in V22 is
-- "delivered divided by planned", so this table is what a delivery percentage is
-- actually counted from. Seed it empty and that indicator has no denominator.
--
-- The same five deliverables go to every project, which is realistic: a software
-- company sells the same life cycle each time. What changes from one project to
-- the next is the STATE, decided by the CASE expressions below.

-- 1. Signed specification, due six weeks in. Validated as soon as the project is
-- really running (COMPLETED or ACTIVE), still waiting otherwise.
INSERT INTO livrables(project_id, titre, description, date_echeance, statut, created_at, updated_at, deleted)
SELECT p.id, 'Cahier des charges validé',
  'Document de spécifications fonctionnelles générales et détaillées, validé par le client.',
  p.start_date + INTERVAL '6 weeks',
  CASE WHEN p.status IN ('COMPLETED','ACTIVE') THEN 'VALIDE' ELSE 'EN_ATTENTE' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- 2. Technical architecture file, due two months in. Same state rule as the
-- specification, on purpose: both are early documents, so both are done as soon
-- as the project has genuinely started.
INSERT INTO livrables(project_id, titre, description, date_echeance, statut, created_at, updated_at, deleted)
SELECT p.id, 'Architecture technique',
  'Dossier d''architecture applicative, infra et sécurité — validé par le comité technique.',
  p.start_date + INTERVAL '2 months',
  CASE WHEN p.status IN ('COMPLETED','ACTIVE') THEN 'VALIDE' ELSE 'EN_ATTENTE' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- 3. Beta version, due six months in. Three states instead of two: VALIDE on a
-- finished project, EN_COURS on a running one, EN_ATTENTE otherwise — so the
-- board shows work in progress and not only done-or-not-done.
-- Worth noticing before a jury asks: the WHERE of THIS statement alone carries
-- no "status != 'DRAFT'" filter, unlike the four others. A DRAFT project
-- therefore ends up with exactly one deliverable, in EN_ATTENTE. Nothing breaks
-- and the state is a sensible one, but the difference does not look deliberate.
INSERT INTO livrables(project_id, titre, description, date_echeance, statut, created_at, updated_at, deleted)
SELECT p.id, 'Application version bêta',
  'Livraison de la version bêta pour recette interne et tests utilisateurs.',
  p.start_date + INTERVAL '6 months',
  CASE WHEN p.status = 'COMPLETED' THEN 'VALIDE'
       WHEN p.status = 'ACTIVE' THEN 'EN_COURS'
       ELSE 'EN_ATTENTE' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE
ON CONFLICT DO NOTHING;

-- 4. End-user training, due three weeks before the project ends.
-- The due date is computed BACKWARDS from the end and not forwards from the
-- start, because training happens just before hand-over whatever the length of
-- the project.
-- COALESCE(p.end_date, p.start_date + INTERVAL '12 months') supplies an end date
-- for a project stored without one. In SQL any arithmetic on NULL gives NULL, so
-- without it those projects would receive a deliverable with NO due date at all,
-- and the "late deliverables" list could never see them — the exact rows a
-- project manager most needs to find.
INSERT INTO livrables(project_id, titre, description, date_echeance, statut, created_at, updated_at, deleted)
SELECT p.id, 'Formation des utilisateurs',
  'Sessions de formation pour les utilisateurs finaux et les administrateurs fonctionnels.',
  COALESCE(p.end_date, p.start_date + INTERVAL '12 months') - INTERVAL '3 weeks',
  CASE WHEN p.status = 'COMPLETED' THEN 'VALIDE' ELSE 'EN_ATTENTE' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- 5. Go-live and signed acceptance report, one week before the end: the last
-- deliverable, and the only one whose finished state is LIVRE rather than
-- VALIDE. The distinction is real — the work is handed over, while the formal
-- validation is the client's signature, which comes later. Using both values
-- also means the demo covers all four states the CHECK of V11 allows.
INSERT INTO livrables(project_id, titre, description, date_echeance, statut, created_at, updated_at, deleted)
SELECT p.id, 'Mise en production et PV de recette',
  'Déploiement en production, PV de recette signé et passation de service.',
  COALESCE(p.end_date, p.start_date + INTERVAL '12 months') - INTERVAL '1 week',
  CASE WHEN p.status = 'COMPLETED' THEN 'LIVRE' ELSE 'EN_ATTENTE' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- ── §14  Parties prenantes ────────────────────────────────────
-- (French original: "Parties prenantes")
-- The stakeholders of each project: the people on the CLIENT side who decide, or
-- who are affected by what is delivered.
-- They are not users of this application and have no account — which is exactly
-- why parties_prenantes stores a name, a job title, an e-mail and a phone number
-- as plain text instead of pointing at users(id).
-- influence and interet are the two axes of the classic stakeholder matrix; V11
-- restricts both to FAIBLE / MOYEN / ELEVE.
-- Two contacts per project, one decision-maker and one day-to-day counterpart,
-- so the matrix has points in more than one square.

-- The client's IT director: high influence, high interest — the top-right corner
-- of the matrix, the person to keep closest.
-- || glues text together, so the name reads "DSI <client>".
--
-- THE REGULAR EXPRESSION, regexp_replace(p.client, '[^a-zA-Z]', '', 'g'):
-- it turns the client's name into something usable as a domain name.
--   [a-zA-Z]  is the set of all letters, upper and lower case.
--   the ^ INSIDE the square brackets means NOT, so [^a-zA-Z] matches every
--             character that is not a letter: spaces, apostrophes, digits,
--             hyphens, accented letters.
--   ''        is the replacement — an empty string, so those characters are
--             simply removed.
--   'g'       is the global flag: replace EVERY match, not just the first one.
-- lower() then puts the result in lower case, so "Banque de l'Habitat" becomes
-- "banquedelhabitat" and the address reads dsi@banquedelhabitat.tn.
-- Without the 'g' flag only the FIRST space would be removed and the address
-- would still contain spaces and an apostrophe, which is not a valid e-mail.
-- Be aware accented letters are not in a-z either, so they go too: "Société"
-- gives "socit". Acceptable here because these are fictional demo contacts that
-- nobody writes to.
--
-- THE PHONE NUMBER, '+216 71 ' || (100000 + floor(random()*899999))::INT:
-- +216 is the country code of Tunisia and 71 the Tunis landline prefix; the
-- arithmetic yields a whole number between 100000 and 999999, so the number is
-- never short of a digit. ::INT casts away the decimals floor() already removed,
-- so the text carries no ".0".
-- Because random() is used, this number is DIFFERENT on every run — unlike the
-- bank references of §7, which are built from the data and come out the same
-- every time. Harmless for a number nobody calls, but worth knowing before
-- anybody compares two databases row by row.
--
-- "p.client IS NOT NULL" is the guard that really matters. The client's name is
-- used three times above, and in SQL concatenating anything with NULL gives
-- NULL. A project with no client would produce a row whose "nom" is NULL — and
-- nom is NOT NULL in V11, so that single row would abort the whole migration.
INSERT INTO parties_prenantes(project_id, nom, fonction, email, telephone, influence, interet, created_at, updated_at, deleted)
SELECT p.id,
  'DSI ' || p.client,
  'Directeur des Systèmes d''Information',
  'dsi@' || lower(regexp_replace(p.client, '[^a-zA-Z]', '', 'g')) || '.tn',
  '+216 71 ' || (100000 + floor(random()*899999))::INT,
  'ELEVE', 'ELEVE',
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT' AND p.client IS NOT NULL
ON CONFLICT DO NOTHING;

-- The client's own functional project manager: MEDIUM influence, high interest —
-- he lives the project every day but does not sign the cheques. Placing the two
-- contacts in different squares of the matrix is the whole point of having two.
-- Same construction as above, with '98' instead of '71': that is a Tunisian
-- MOBILE prefix rather than a landline one, the small detail that makes the two
-- contacts read as different people on screen.
INSERT INTO parties_prenantes(project_id, nom, fonction, email, telephone, influence, interet, created_at, updated_at, deleted)
SELECT p.id,
  'Chef de projet ' || p.client,
  'Responsable Projet Fonctionnel Côté Client',
  'projet@' || lower(regexp_replace(p.client, '[^a-zA-Z]', '', 'g')) || '.tn',
  '+216 98 ' || (100000 + floor(random()*899999))::INT,
  'MOYEN', 'ELEVE',
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT' AND p.client IS NOT NULL
ON CONFLICT DO NOTHING;

-- ── §15  Demandes de changement ───────────────────────────────
-- (French original: "Demandes de changement")
-- Change requests: what the client asked for that was not in the contract.
-- This is the governance counterpart of the avenants of §8 — a change request is
-- the DEMAND, an avenant is the signed contract that follows once it is
-- accepted. Seeing both lets a jury follow one change from request to amendment.
-- V11 restricts priorite to FAIBLE / NORMALE / ELEVEE / CRITIQUE and statut to
-- EN_ATTENTE / APPROUVE / REJETE.
--
-- demandeur_id is the project's own manager. In this company the request reaches
-- the tool through the project manager, who records it; and the column is a
-- foreign key to users, so it HAS to be somebody with an account — the client
-- himself has none, which is precisely the point made in §14.

-- Request 1: an extra reporting module. Priority NORMALE, already APPROUVE.
-- The decision is dated one month after the request (raised at start + 4 months,
-- decided at start + 5 months), which gives the governance screen a realistic
-- delay to display instead of a decision taken the same day.
-- Eleven projects, listed by code, all COMPLETED or ACTIVE.
-- Worth knowing before a jury cross-checks: this list OVERLAPS the eleven
-- projects that receive an avenant in §8 but is not identical — nine codes are
-- common, and two differ on each side. So a few approved change requests here
-- have no matching amendment behind them, and vice versa. The two sections were
-- written independently of each other.
INSERT INTO demandes_changement(project_id, demandeur_id, titre, description, priorite, statut, date_demande, date_decision, created_at, updated_at, deleted)
SELECT p.id, p.chef_projet_id,
  'Ajout d''un module de reporting complémentaire',
  'Le client souhaite des états de synthèse supplémentaires non prévus au contrat initial, notamment un tableau de bord exécutif et des exports Excel automatisés.',
  'NORMALE', 'APPROUVE',
  p.start_date + INTERVAL '4 months',
  p.start_date + INTERVAL '5 months',
  NOW(), NOW(), false
FROM projects p
WHERE p.deleted=FALSE AND p.status IN ('COMPLETED','ACTIVE')
  AND p.code IN ('S2I-2022-001','S2I-2022-002','S2I-2023-003','S2I-2023-004',
                 'S2I-2024-006','S2I-2024-007','S2I-2024-011','S2I-2022-021',
                 'S2I-2022-027','S2I-2023-028','S2I-2023-048')
ON CONFLICT DO NOTHING;

-- Request 2: a framework upgrade forced by the client's own security policy.
-- Priority ELEVEE and already APPROUVE, decided just ten days after being raised
-- — a security matter is not left sitting in a queue, and the short delay is
-- what makes the priority visible in the data rather than only in the label.
-- ACTIVE projects only, five of them.
INSERT INTO demandes_changement(project_id, demandeur_id, titre, description, priorite, statut, date_demande, date_decision, created_at, updated_at, deleted)
SELECT p.id, p.chef_projet_id,
  'Migration vers une nouvelle version du framework',
  'Mise à niveau imposée par la politique de sécurité du client. Impact évalué à 5 JH supplémentaires.',
  'ELEVEE', 'APPROUVE',
  p.start_date + INTERVAL '7 months',
  p.start_date + INTERVAL '7 months' + INTERVAL '10 days',
  NOW(), NOW(), false
FROM projects p
WHERE p.deleted=FALSE AND p.status = 'ACTIVE'
  AND p.code IN ('S2I-2023-004','S2I-2024-011','S2I-2023-022','S2I-2023-033','S2I-2023-048')
ON CONFLICT DO NOTHING;

-- Request 3: switching to the government SSO (single sign-on: one login shared
-- by several applications). Priority CRITIQUE, and still EN_ATTENTE.
-- Look carefully at the column list of THIS statement: date_decision is ABSENT,
-- where the two statements above both name it. That is the whole point of the
-- statement. A request that has not been decided has no decision date, so the
-- column is left NULL. Writing a date there would claim the request was settled
-- while its statut says it is still open, and the two fields of the same row
-- would contradict each other in front of the reader.
-- Having at least one pending request is also what lets the "waiting for a
-- decision" filter of the governance screen be demonstrated with a result in it.
INSERT INTO demandes_changement(project_id, demandeur_id, titre, description, priorite, statut, date_demande, created_at, updated_at, deleted)
SELECT p.id, p.chef_projet_id,
  'Intégration avec un nouveau partenaire SSO',
  'Suite à une décision de direction, l''authentification doit passer par le SSO gouvernemental. Analyse d''impact en cours.',
  'CRITIQUE', 'EN_ATTENTE',
  p.start_date + INTERVAL '8 months',
  NOW(), NOW(), false
FROM projects p
WHERE p.deleted=FALSE AND p.status = 'ACTIVE'
  AND p.code IN ('S2I-2023-004','S2I-2024-011','S2I-2024-010','S2I-2022-027')
ON CONFLICT DO NOTHING;

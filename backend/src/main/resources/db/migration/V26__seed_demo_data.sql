-- V26: demo data for Société S2I (Tunisia, TND) — 50 projects, 36 users,
-- 2022-2026. Fills the tables V1-V25 created so the app can be shown with real
-- volumes instead of empty screens; sections follow table dependency order.
--
-- Deliberately contains NO Devis Interne lines (table lignes_di, V23): per the
-- 2026-07-05 decision, DI stays an empty structure and real salary/margin
-- figures are never committed to the repo. This file stops at project budgets.
--
-- Demo passwords below are written in clear text on purpose (handed out during
-- a demo) and hashed by pgcrypto before storage. Never run against production.

-- pgcrypto adds crypt()/gen_salt(), used in §1 to hash the demo passwords.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── §-1  Clean-up (idempotence — safe to re-run) ──────────────
-- Deletes only rows with the S2I-* prefix or the demo accounts, so the file
-- can be replayed after a partial failure without touching real data the way
-- a TRUNCATE would.
DO $$
-- Held as an array so the whole clean-up runs off one SELECT instead of
-- repeating the same sub-query in fourteen DELETEs.
DECLARE v_ids BIGINT[];
BEGIN
  SELECT ARRAY_AGG(id) INTO v_ids FROM projects WHERE code LIKE 'S2I-%' AND deleted = FALSE;
  -- ARRAY_AGG over zero rows returns NULL, not []; without this guard the
  -- block would still run fourteen no-op statements on a normal first run.
  IF v_ids IS NOT NULL THEN
    -- Reverse creation order: a row is deleted before the row it points at.
    DELETE FROM demandes_changement  WHERE project_id = ANY(v_ids);
    DELETE FROM parties_prenantes    WHERE project_id = ANY(v_ids);
    DELETE FROM livrables            WHERE project_id = ANY(v_ids);
    DELETE FROM risks                WHERE project_id = ANY(v_ids);
    -- Two levels down: mission components hang off missions, which hang off projects.
    DELETE FROM composantes_mission  WHERE mission_id IN (SELECT id FROM missions WHERE project_id = ANY(v_ids));
    DELETE FROM missions             WHERE project_id = ANY(v_ids);
    DELETE FROM snapshot_kpis        WHERE project_id = ANY(v_ids);
    DELETE FROM charges_reelles      WHERE project_id = ANY(v_ids);
    DELETE FROM plan_charges         WHERE project_id = ANY(v_ids);
    DELETE FROM avenants             WHERE project_id = ANY(v_ids);
    -- Same two-level shape: a payment hangs off a milestone, which hangs off a project.
    DELETE FROM paiements            WHERE jalon_id IN (SELECT id FROM jalons_facturation WHERE project_id = ANY(v_ids));
    DELETE FROM jalons_facturation   WHERE project_id = ANY(v_ids);
    DELETE FROM team_assignments     WHERE project_id = ANY(v_ids);
    DELETE FROM projects             WHERE id = ANY(v_ids);
  END IF;
  -- Outside the IF: demo users/resources can exist even with no demo project
  -- (e.g. a previous run stopped between §1 and §4).
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
-- Simulated company is Tunisian, so the default currency becomes TND.
-- UPDATE not INSERT: the CURRENCY row already exists, seeded by an earlier migration.
UPDATE parameters SET param_value = 'TND', updated_at = NOW() WHERE param_key = 'CURRENCY';

-- ── §1  Users ─────────────────────────────────────────────────
-- 36 accounts across the four RBAC roles, inserted role by role. first_login =
-- false so the demo isn't interrupted by the forced password-change filter.
--
-- Momo accounts (password: Momo123456) — one per role, used for demos.
-- crypt(..., gen_salt('bf',12)): bcrypt hashing, cost 12, matching
-- BCryptPasswordEncoder's expectation; a fresh salt per row means identical
-- passwords still get different hashes. Role looked up by name (ids are
-- BIGSERIAL and differ per database). created_at is backdated so the account
-- list doesn't show 36 people hired the same afternoon.
INSERT INTO users (first_name, last_name, email, password_hash, active, first_login, role_id, created_at, updated_at, deleted) VALUES
('Mohamed', 'Ben Directeur', 'momo-directeur@pms.local', crypt('Momo123456', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DIRECTEUR'),   NOW()-INTERVAL'2 years', NOW(), false),
('Mohamed', 'Ben Chef',      'momo-chef@pms.local',      crypt('Momo123456', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'),  NOW()-INTERVAL'3 years', NOW(), false),
('Mohamed', 'Ben Dev',       'momo-dev@pms.local',       crypt('Momo123456', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DEVELOPPEUR'),  NOW()-INTERVAL'1 year',  NOW(), false)
-- Targets the partial unique index uk_users_email (V18, WHERE deleted=FALSE);
-- DO UPDATE (not DO NOTHING) resets these three accounts' passwords so a demo
-- always has a known-good login.
ON CONFLICT (email) WHERE deleted = FALSE DO UPDATE SET password_hash=EXCLUDED.password_hash, role_id=EXCLUDED.role_id, first_login=false, updated_at=NOW();

-- Additional directors (password: PmsUser2025!). From here on plain ON
-- CONFLICT DO NOTHING: an existing account is left as-is.
INSERT INTO users (first_name, last_name, email, password_hash, active, first_login, role_id, created_at, updated_at, deleted) VALUES
('Karim',   'Ben Salah',  'karim.bensalah@s2i.tn',  crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DIRECTEUR'), NOW()-INTERVAL'6 years', NOW(), false),
('Nadia',   'Trabelsi',   'nadia.trabelsi@s2i.tn',   crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='DIRECTEUR'), NOW()-INTERVAL'5 years', NOW(), false)
ON CONFLICT DO NOTHING;

-- Project managers (password: PmsUser2025!) — seven, so the 50-project
-- portfolio below isn't all owned by one manager.
INSERT INTO users (first_name, last_name, email, password_hash, active, first_login, role_id, created_at, updated_at, deleted) VALUES
('Sonia',  'Hammami',    'sonia.hammami@s2i.tn',    crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'5 years', NOW(), false),
('Aymen',  'Chaouachi',  'aymen.chaouachi@s2i.tn',  crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'4 years', NOW(), false),
('Riadh',  'Mabrouk',    'riadh.mabrouk@s2i.tn',    crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'5 years', NOW(), false),
('Wafa',   'Bousbia',    'wafa.bousbia@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'4 years', NOW(), false),
('Tarek',  'Ghannouchi', 'tarek.ghannouchi@s2i.tn', crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'6 years', NOW(), false),
('Inès',   'Dridi',      'ines.dridi@s2i.tn',       crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'3 years', NOW(), false),
('Mehdi',  'Karray',     'mehdi.karray@s2i.tn',     crypt('PmsUser2025!', gen_salt('bf',12)), true, false, (SELECT id FROM roles WHERE name='CHEF_PROJET'), NOW()-INTERVAL'4 years', NOW(), false)
ON CONFLICT DO NOTHING;

-- Developers — senior (password: PmsUser2025!). Seniority is only a demo-data
-- split for §2's cost prices; the app itself only knows the DEVELOPPEUR role.
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
-- The cost side of a user: daily_rate (TND/day), tcc_rate (loading fraction,
-- 0.4300 = 43%), staffing_start. Kept apart from users because not every
-- account has a cost and MANAGE_RESOURCES guards it separately. Looked up by
-- e-mail (not id) so a rate always attaches to the right person.
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

-- Senior developers — highest daily cost of the three levels.
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

-- Junior developers — lowest daily cost. The spread across the three levels is
-- what makes a project's margin depend on WHO is staffed on it.
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
-- Cost per man-day depends on the YEAR it's charged to (V21, F-AFF-13 §6.3
-- rule 4), so a multi-year project must value each year's days at that year's
-- rate. Generated as a loop (~5%/year progression) rather than hand-written,
-- so a resource added later is never missed.
DO $$
DECLARE r RECORD;
BEGIN
  -- deleted = FALSE: no point pricing a resource nobody can use.
  FOR r IN SELECT res.id, res.daily_rate, res.tcc_rate, res.staffing_start
           FROM resources res WHERE res.deleted = FALSE
  LOOP
    -- 2025 is the reference year (kept as-is); 2023/2024 are derived
    -- (×0.905, ×0.955) to reproduce ~5%/year growth. round(...,2) matches the
    -- NUMERIC(10,2) column. The TCC ratio moves by a smaller fraction since
    -- charges move slower than salaries.
    INSERT INTO tcc_annuels (resource_id, annee, daily_rate, tcc_rate, created_at, updated_at, deleted) VALUES
      (r.id, 2023, round(r.daily_rate * 0.905, 2), r.tcc_rate - 0.0050, NOW(), NOW(), false),
      (r.id, 2024, round(r.daily_rate * 0.955, 2), r.tcc_rate - 0.0020, NOW(), NOW(), false),
      (r.id, 2025, r.daily_rate,                   r.tcc_rate,          NOW(), NOW(), false)
    -- (resource_id, annee) is unique (V21); a hand-entered rate wins over this one.
    ON CONFLICT DO NOTHING;
  END LOOP;
END $$;

-- ── §4  Projects (50) ─────────────────────────────────────────
-- Portfolio spread over 2022-2026 and four statuses, so list filters and KPI
-- comparisons (finished vs running) have something to show.
--   DRAFT: signed, not started. ACTIVE: running. COMPLETED: finished.
--   initial_budget/revised_budget: at signature, and after amendments (NULL
--     until something changes; computations read "revised if present").
--   currency + exchange_rate_to_tnd: conversion point to TND (ADR-020); all
--     demo projects are TND, rate 1.
--   sold_workload_days: man-days SOLD — what V22's drift is measured against.
--   marge_nette_vendue: sold net margin baseline (V22), as a fraction.
--   director_id / chef_projet_id: looked up by e-mail, not literal id.
--
-- ── momo-chef's projects (20) ─────────────────────────────────
-- Twenty on one manager (the demo account) so pagination/filters/picker have
-- something to show.
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
-- Split across the seven other managers so "my projects" filters have
-- something to exclude, and ADR-021's scope rule can be demonstrated (403 on
-- a project that isn't the caller's, despite holding the permission).
-- These statements omit penalty_provision (momo-chef's don't): an omitted
-- column just takes its default (NULL) — "no penalty clause recorded".
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
-- Who works on which project — read by ADR-021's ProjectScopeInterceptor to
-- allow /api/projects/{id}/** access. Without this section every demo account
-- gets 403 on every project screen.
--
-- Projects of momo-chef — developers
-- CROSS JOIN filtered by (p.code, u.email) IN (...) — a row comparison — so
-- one statement creates every (project, person) pairing listed, with no ids
-- to keep in sync.
INSERT INTO team_assignments (project_id, user_id, role_in_team, start_date, end_date, created_at, updated_at, deleted)
-- Dates copied from the project itself: the assignment spans its whole life.
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

-- momo-chef manages his own twenty projects too (role_in_team CHEF_PROJET):
-- needed because the scope check reads team_assignments, not just
-- projects.chef_projet_id. role_in_team is a per-project label, not the RBAC role.
INSERT INTO team_assignments (project_id, user_id, role_in_team, start_date, end_date, created_at, updated_at, deleted)
SELECT p.id, (SELECT id FROM users WHERE email='momo-chef@pms.local'), 'CHEF_PROJET', p.start_date, p.end_date, NOW(), NOW(), false
FROM projects p
WHERE p.code IN ('S2I-2022-001','S2I-2022-002','S2I-2023-003','S2I-2023-004','S2I-2023-005',
                 'S2I-2024-006','S2I-2024-007','S2I-2024-008','S2I-2024-009','S2I-2024-010',
                 'S2I-2024-011','S2I-2024-012','S2I-2024-013','S2I-2024-014','S2I-2025-015',
                 'S2I-2025-016','S2I-2025-017','S2I-2025-018','S2I-2025-019','S2I-2025-020')
ON CONFLICT DO NOTHING;

-- Other managers: p.chef_projet_id read straight off the project row, so this
-- can never disagree with the project sheet.
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

-- Developers on the other managers' projects. Same (code, e-mail) pairing;
-- several developers appear on multiple projects, which is what the workload
-- screens are meant to show.
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
-- Pattern of 4: 20% kick-off · 40% mid-way · 30% delivery · 10% end of
-- warranty. Generated by a loop rather than 50 hand-written sets (200 lines
-- that could silently stop summing to 100%).
DO $$
DECLARE
  p RECORD;       -- the project being processed
  b NUMERIC;      -- its reference budget
  d0 DATE; d_end DATE;   -- its start and end dates
  dur_months INT;        -- its duration in months
BEGIN
  FOR p IN SELECT id, code, initial_budget, revised_budget, start_date, end_date, status
           FROM projects WHERE deleted = FALSE ORDER BY code
  LOOP
    -- Reference budget: revised if set, else initial, else a 100000 fallback
    -- so no project ends up with NULL milestone amounts.
    b     := COALESCE(p.revised_budget, p.initial_budget, 100000);
    d0    := COALESCE(p.start_date, '2023-01-01');
    d_end := COALESCE(p.end_date, d0 + INTERVAL '12 months');
    -- Duration in whole months, floored at 3 so the mid-way milestone (below,
    -- at half the duration) never lands on day one.
    dur_months := GREATEST(3, EXTRACT(MONTH FROM AGE(d_end, d0))::INT);

    -- Milestone 1: 20% at kick-off. Billed (PAYE) once the project has at
    -- least started (ACTIVE/COMPLETED); still PREVU on a DRAFT.
    INSERT INTO jalons_facturation(project_id, label, pourcentage, montant, date_prevue, date_facture, statut, created_at, updated_at, deleted)
    VALUES(p.id, 'Démarrage et installation', 20, round(b*0.20,2),
      d0 + INTERVAL '1 month',
      CASE WHEN p.status IN ('COMPLETED','ACTIVE') THEN d0 + INTERVAL '5 weeks' ELSE NULL END,
      CASE WHEN p.status IN ('COMPLETED','ACTIVE') THEN 'PAYE' ELSE 'PREVU' END,
      NOW(), NOW(), false);

    -- Milestone 2: 40% mid-way. COMPLETED = PAYE; ACTIVE past its midpoint
    -- (dur_months > 8) = FACTURE (invoiced, not yet cashed — what gives FAE a
    -- non-zero value); otherwise PREVU.
    INSERT INTO jalons_facturation(project_id, label, pourcentage, montant, date_prevue, date_facture, statut, created_at, updated_at, deleted)
    VALUES(p.id, 'Livraison intermédiaire', 40, round(b*0.40,2),
      -- No "INTERVAL n months" with a variable in Postgres, hence the
      -- text-concat-then-cast detour. dur_months/2 is integer division on purpose.
      d0 + (dur_months/2 || ' months')::INTERVAL,
      CASE WHEN p.status = 'COMPLETED' THEN d0 + ((dur_months/2)+1 || ' months')::INTERVAL
           WHEN p.status = 'ACTIVE' AND dur_months > 8 THEN d0 + ((dur_months/2)+1 || ' months')::INTERVAL
           ELSE NULL END,
      CASE WHEN p.status = 'COMPLETED' THEN 'PAYE'
           WHEN p.status = 'ACTIVE' AND dur_months > 8 THEN 'FACTURE'
           ELSE 'PREVU' END,
      NOW(), NOW(), false);

    -- Milestone 3: 30% at delivery, dated back from the project's end (not
    -- forward from the start). Only a COMPLETED project reaches it.
    INSERT INTO jalons_facturation(project_id, label, pourcentage, montant, date_prevue, date_facture, statut, created_at, updated_at, deleted)
    VALUES(p.id, 'Recette et déploiement', 30, round(b*0.30,2),
      d_end - INTERVAL '1 month',
      CASE WHEN p.status = 'COMPLETED' THEN d_end - INTERVAL '2 weeks' ELSE NULL END,
      CASE WHEN p.status = 'COMPLETED' THEN 'PAYE' ELSE 'PREVU' END,
      NOW(), NOW(), false);

    -- Milestone 4: 10% at end of warranty, 3 months after the project ends —
    -- billed after §10's snapshot date for a finished project, which is why
    -- that snapshot records 90% invoiced, not 100%. (20+40+30+10 = 100.)
    INSERT INTO jalons_facturation(project_id, label, pourcentage, montant, date_prevue, date_facture, statut, created_at, updated_at, deleted)
    VALUES(p.id, 'Fin de garantie', 10, round(b*0.10,2),
      d_end + INTERVAL '3 months',
      CASE WHEN p.status = 'COMPLETED' THEN d_end + INTERVAL '3 months' + INTERVAL '2 weeks' ELSE NULL END,
      CASE WHEN p.status = 'COMPLETED' THEN 'PAYE' ELSE 'PREVU' END,
      NOW(), NOW(), false);
  END LOOP;
END $$;

-- ── §7  Payments (milestones marked PAYE) ─────────────────────
-- One payment per §6 milestone marked PAYE, derived from it so the two can
-- never disagree.
INSERT INTO paiements (jalon_id, montant_recu, date_paiement, reference, created_at, updated_at, deleted)
-- Paid in full, 15 days after the invoice (a normal delay).
SELECT jf.id, jf.montant, jf.date_facture + INTERVAL '15 days',
  -- Reference built from the data (not random) so it's reproducible and traceable.
  'VIR-' || to_char(jf.date_facture, 'YYYYMMDD') || '-' || jf.project_id,
  NOW(), NOW(), false
FROM jalons_facturation jf
-- date_facture is used twice above; NULL would silently produce a payment
-- with no date and an empty reference, hence the guard.
WHERE jf.statut = 'PAYE' AND jf.date_facture IS NOT NULL AND jf.deleted = FALSE
ON CONFLICT DO NOTHING;

-- ── §8  Contract amendments ("avenants") ──────────────────────
-- Eleven projects get one amendment, four of those get a second, so the demo
-- shows a real gap between initial_budget and revised_budget rather than an
-- amendment on every project. Note: this does not touch
-- projects.revised_budget — that was written directly in §4, so the two stay
-- consistent only because they were written to agree.
INSERT INTO avenants (project_id, numero, objet, montant, workload_days, date_avenant, created_at, updated_at, deleted)
-- Number built from the project code so it's unique and self-explanatory.
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

-- Second amendment for four of those projects: smaller (4%) and four months
-- later, for a plausible history.
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
-- Planned and real workload, month by month, for every developer of every
-- project. Almost every KPI/EVM figure is computed from this pair of tables;
-- leave them empty and everything reads zero.
-- Three nested loops (not literal rows) because the volume — thousands of
-- rows — is what makes the workload charts/filters/pagination worth showing.
DO $$
DECLARE
  proj  RECORD;    -- the project being processed
  mbr   RECORD;    -- one developer of that project
  m     DATE;      -- the month being processed, always the 1st of the month
  p_days NUMERIC;  -- days planned for that person, that month
  a_days NUMERIC;  -- days really reported for that person, that month
  v_by  BIGINT;    -- fallback validator, see below
BEGIN
  -- Looked up once (constant) rather than inside the loops.
  -- charges_reelles.validated_by is the fallback for a project with no
  -- manager — a validation date with no validator would be a red flag for an auditor.
  SELECT id INTO v_by FROM users WHERE email = 'momo-chef@pms.local';

  -- Every project, DRAFT included: a project being prepared can have a team
  -- and a plan already; the inner IF below is what withholds REAL charges.
  FOR proj IN
    SELECT p.id, p.start_date, p.end_date, p.status, p.code, p.chef_projet_id
    FROM projects p WHERE p.deleted = FALSE
  LOOP
    -- DEVELOPPEUR members only — the manager is skipped since §5 already
    -- gives him a CHEF_PROJET row; counting him here would inflate every
    -- project's workload by one phantom person.
    FOR mbr IN
      SELECT ta.user_id
      FROM team_assignments ta
      WHERE ta.project_id = proj.id AND ta.deleted = FALSE
        AND ta.role_in_team = 'DEVELOPPEUR'
    LOOP
      -- period is a MONTH KEY; date_trunc keeps every row on the 1st, so
      -- V7's partial unique index (project_id, user_id, period) works correctly.
      m := date_trunc('month', proj.start_date);
      -- One turn per month, stopping at the project's end or 2026-08-01
      -- (the demo's hard horizon), whichever comes first.
      WHILE m < LEAST(COALESCE(proj.end_date, '2026-12-01'::DATE), '2026-08-01'::DATE) LOOP
        -- Planned: 14-21 days with natural variance, so charts aren't a flat
        -- line. Range matches V7's CHECK (planned_days <= 31) and a believable
        -- part-time share of a month.
        p_days := 14 + (floor(random() * 8))::NUMERIC;

        -- ON CONFLICT with no target covers uk_pc_active (V7); needed so a
        -- manual replay (without the §-1 clean-up) doesn't abort on the first
        -- duplicate month.
        INSERT INTO plan_charges(project_id, user_id, period, planned_days, created_at, updated_at, deleted)
        VALUES(proj.id, mbr.user_id, m, p_days, NOW(), NOW(), false)
        ON CONFLICT DO NOTHING;

        -- Real charges: past months only (before 2026-07-01), so the newest
        -- demo month is planned but not yet reported — a month "in progress".
        IF m < '2026-07-01'::DATE THEN
          -- Derived from p_days (±3 days) rather than drawn independently, so
          -- plan and reality stay close instead of looking catastrophically off track.
          a_days := p_days + (-3 + floor(random() * 7))::NUMERIC;
          -- Clamped to [0, 22]: V7 forbids negative days, and 22 is roughly a
          -- month's working days.
          a_days := GREATEST(0, LEAST(a_days, 22));

          -- submitted/validated a few days apart near month-end. Left NULL,
          -- the KPI engine (which only counts VALIDATED workload) would read
          -- zero everywhere despite the rows existing. validated_by falls
          -- back to v_by when the project has no manager.
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

        -- Advances the WHILE loop; removing this line would make it infinite.
        m := m + INTERVAL '1 month';
      END LOOP;
    END LOOP;
  END LOOP;
END $$;

-- ── §10  Snapshots KPI ────────────────────────────────────────
-- One row = the monthly project review of ONE project at ONE date — a
-- PHOTOGRAPH, frozen and never recomputed, unlike the live KPI screen.
-- Generated here because no amount of clicking can create a past; without it
-- the trend charts would hold a single point.
--   COMPLETED: one final snapshot, at the project's end.
--   ACTIVE: three monthly snapshots, for a trend.
--   DRAFT: none — nothing to measure yet (hence IF/ELSIF, no ELSE).
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
  FOR p IN SELECT pr.id, pr.status, pr.initial_budget, pr.revised_budget,
                  pr.start_date, pr.end_date, pr.marge_nette_vendue, pr.sold_workload_days
           FROM projects pr WHERE pr.deleted = FALSE
  LOOP
    -- Same reference-budget rule as §6's milestones, so the invoiced share
    -- computed below matches the invoicing plan.
    b := COALESCE(p.revised_budget, p.initial_budget, 100000);

    IF p.status = 'COMPLETED' THEN
      snap_d   := COALESCE(p.end_date, '2024-12-31');
      -- 98-100% done, not a flat 100: a finished project can still carry a
      -- last reserve.
      ev       := 98 + floor(random() * 3);            -- 98-100%
      -- 87-105% of budget spent — crosses 100 on purpose so the portfolio
      -- has both profitable and overrun projects.
      consumed := b * (0.87 + random() * 0.18);
      ca_prod  := b * ev / 100;
      -- 90% invoiced, not 100: §6's last milestone (10%, end of warranty) is
      -- billed 3 months after the project ends, after this snapshot's date —
      -- the gap that makes FAE non-zero.
      total_f  := b * 0.90;                            -- facturé 90% (garantie à venir)
      margin   := ca_prod - consumed;

      -- eac = budget_consome here: for a finished project the forecast IS the
      -- final cost. delivery_pct=100/raf_jh=0: nothing left to do.
      -- round() precisions (2 for money, 4 for ratios) match V22's columns.
      -- NULLIF(ca_prod,0) avoids a division-by-zero abort.
      -- derive_jh/date_fin_estimee stay NULL: no meaning left on a finished project.
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
      -- Three FIXED dates (not a moving window) so the demo looks the same
      -- every time it's played.
      FOREACH snap_d IN ARRAY ARRAY['2026-04-30'::DATE,'2026-05-31'::DATE,'2026-06-30'::DATE]
      LOOP
        -- 40-84% done, drawn fresh each turn — progress can dip month to month.
        ev       := 40 + floor(random() * 45);         -- 40-84%
        consumed := b * ev / 100 * (0.90 + random() * 0.20);
        ca_prod  := b * ev / 100;
        -- Invoicing lags progress (billed at milestones, not continuously) —
        -- that gap is FAE. Capped at 70% since §6 bills a running project at
        -- most 60% (20% kick-off + 40% mid-way).
        total_f  := b * LEAST(ev/100 * 0.80, 0.70);
        margin   := ca_prod - consumed;

        -- eac: budget ±5% (a running project's EAC is a guess, which is what
        -- a review argues about). delivery_pct drifts ±2 points from ev on
        -- purpose — V22 keeps them side by side so a review can ask why they
        -- disagree. consomme_jh/raf_jh split sold workload by progress, with
        -- the remainder priced 10% cheaper (a team that has learned the project).
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
-- A trip for a project (kick-off, workshop, steering committee); the cost of
-- the trip is broken down as "composantes" further down.
-- Every row is INSERT ... SELECT FROM projects, dated off each project's own
-- start date, so 50 projects are covered by three statements that can't drift
-- apart from the project sheet.
-- Note: ON CONFLICT DO NOTHING in §11-§15 catches nothing (these tables have
-- no unique key beyond their BIGSERIAL id) — what makes the file replayable
-- is the §-1 clean-up, not this clause.

-- Mission 1: kick-off workshop, one week in, three days. lieu = the client's
-- name (nullable, for a project with none). Excludes DRAFT: no kick-off held yet.
INSERT INTO missions(project_id, user_id, objet, lieu, date_debut, date_fin, created_at, updated_at, deleted)
SELECT p.id, p.chef_projet_id,
  'Réunion de cadrage et atelier de lancement avec l''équipe client',
  p.client,
  p.start_date + INTERVAL '1 week',
  p.start_date + INTERVAL '1 week' + INTERVAL '2 days',
  NOW(), NOW(), false
FROM projects p WHERE p.deleted = FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- Mission 2: spec validation workshop, two months in. COMPLETED only, so
-- finished projects carry a richer travel history than running ones.
INSERT INTO missions(project_id, user_id, objet, lieu, date_debut, date_fin, created_at, updated_at, deleted)
SELECT p.id, p.chef_projet_id,
  'Atelier de validation des spécifications fonctionnelles',
  p.client,
  p.start_date + INTERVAL '2 months',
  p.start_date + INTERVAL '2 months' + INTERVAL '1 day',
  NOW(), NOW(), false
FROM projects p WHERE p.deleted = FALSE AND p.status = 'COMPLETED'
ON CONFLICT DO NOTHING;

-- Mission 3: mid-project steering committee, five months in, a one-day trip
-- (date_debut = date_fin, legal per V10's CHECK). Explicit code list, twelve
-- projects, so not every project shows the same three missions.
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

-- Mission components: V10 allows PERDIEM, BILLET, TIMBRE, TRANSPORT, SEJOUR —
-- the demo uses the two on every real expense claim.

-- Flat 180 TND plane ticket on every mission. Hangs off missions (not
-- projects), so must run after the INSERTs above.
INSERT INTO composantes_mission(mission_id, type_composante, montant, devise, description, created_at, updated_at, deleted)
SELECT m.id, 'BILLET', 180.00, 'TND', 'Billet d''avion aller-retour Tunis–destination', NOW(), NOW(), false
FROM missions m WHERE m.deleted = FALSE
ON CONFLICT DO NOTHING;

-- Per diem: 85 TND × nights (+1 to count both ends: the 8th to the 10th is 3
-- days). Excludes the one-day missions of statement 3 (a data choice, not
-- schema-forced — V10 only requires montant > 0).
INSERT INTO composantes_mission(mission_id, type_composante, montant, devise, description, created_at, updated_at, deleted)
SELECT m.id, 'PERDIEM', 85.00 * (m.date_fin - m.date_debut + 1), 'TND',
  'Per diem journalier × ' || (m.date_fin - m.date_debut + 1) || ' jour(s)',
  NOW(), NOW(), false
FROM missions m WHERE m.deleted = FALSE AND m.date_fin > m.date_debut
ON CONFLICT DO NOTHING;

-- ── §12  Risques ──────────────────────────────────────────────
-- Risk register per project. V11 restricts probabilite/impact to
-- FAIBLE/MOYEN/ELEVE and statut to OUVERT/MITIGE/FERME.
-- Two risks go to every started project, two more to a chosen list, so
-- registers differ from project to project.

-- Risk 1: client environments delivered late — every started project.
-- Closed (FERME) once the project is COMPLETED, else OUVERT.
INSERT INTO risks(project_id, description, probabilite, impact, plan_mitigation, statut, created_at, updated_at, deleted)
SELECT p.id,
  'Retard de disponibilité des environnements client (recette, intégration)',
  'MOYEN','ELEVE',
  'Prévoir des environnements propres hébergés par S2I et procéder à la migration en fin de phase.',
  CASE WHEN p.status='COMPLETED' THEN 'FERME' ELSE 'OUVERT' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- Risk 2: scope never firmly agreed — the cause behind §8's amendments.
-- Ends on MITIGE (not FERME) when finished: contained, not eliminated —
-- exercises all three CHECK values.
INSERT INTO risks(project_id, description, probabilite, impact, plan_mitigation, statut, created_at, updated_at, deleted)
SELECT p.id,
  'Périmètre fonctionnel insuffisamment défini — risque de dérive des charges',
  'MOYEN','MOYEN',
  'Geler le périmètre en phase de cadrage. Tout ajout passe par un avenant signé.',
  CASE WHEN p.status='COMPLETED' THEN 'MITIGE' ELSE 'OUVERT' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- Risk 3: key people leaving mid-project — 8 ACTIVE projects only, so the
-- register isn't identical everywhere.
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

-- Risk 4: legacy system integration — the only ELEVE/ELEVE risk, six
-- projects, so the governance heat map isn't a single flat colour.
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
-- Deliverables owed to the client, each with a due date and state (V11:
-- EN_ATTENTE, EN_COURS, LIVRE, VALIDE). delivery_pct in V22 is "delivered
-- divided by planned", so this table is that indicator's denominator.
-- Same five deliverables on every project; only the STATE (the CASE below) differs.

-- 1. Signed specification, due six weeks in. Validated once the project has
-- genuinely started (COMPLETED/ACTIVE).
INSERT INTO livrables(project_id, titre, description, date_echeance, statut, created_at, updated_at, deleted)
SELECT p.id, 'Cahier des charges validé',
  'Document de spécifications fonctionnelles générales et détaillées, validé par le client.',
  p.start_date + INTERVAL '6 weeks',
  CASE WHEN p.status IN ('COMPLETED','ACTIVE') THEN 'VALIDE' ELSE 'EN_ATTENTE' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- 2. Technical architecture, due two months in — same state rule as #1.
INSERT INTO livrables(project_id, titre, description, date_echeance, statut, created_at, updated_at, deleted)
SELECT p.id, 'Architecture technique',
  'Dossier d''architecture applicative, infra et sécurité — validé par le comité technique.',
  p.start_date + INTERVAL '2 months',
  CASE WHEN p.status IN ('COMPLETED','ACTIVE') THEN 'VALIDE' ELSE 'EN_ATTENTE' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- 3. Beta version, due six months in. Three states (VALIDE/EN_COURS/
-- EN_ATTENTE) so the board shows in-progress work too. Note: this statement's
-- WHERE has no "status != 'DRAFT'" filter, so a DRAFT project gets exactly
-- this one deliverable, EN_ATTENTE.
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

-- 4. End-user training, due three weeks BEFORE the end (training happens just
-- before hand-over regardless of project length). COALESCE supplies an end
-- date so a project without one still gets a due date the "late deliverables"
-- list can find.
INSERT INTO livrables(project_id, titre, description, date_echeance, statut, created_at, updated_at, deleted)
SELECT p.id, 'Formation des utilisateurs',
  'Sessions de formation pour les utilisateurs finaux et les administrateurs fonctionnels.',
  COALESCE(p.end_date, p.start_date + INTERVAL '12 months') - INTERVAL '3 weeks',
  CASE WHEN p.status = 'COMPLETED' THEN 'VALIDE' ELSE 'EN_ATTENTE' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- 5. Go-live and signed acceptance report, one week before the end — the only
-- one whose done state is LIVRE (handed over) rather than VALIDE (formally
-- signed off later), exercising all four CHECK states.
INSERT INTO livrables(project_id, titre, description, date_echeance, statut, created_at, updated_at, deleted)
SELECT p.id, 'Mise en production et PV de recette',
  'Déploiement en production, PV de recette signé et passation de service.',
  COALESCE(p.end_date, p.start_date + INTERVAL '12 months') - INTERVAL '1 week',
  CASE WHEN p.status = 'COMPLETED' THEN 'LIVRE' ELSE 'EN_ATTENTE' END,
  NOW(), NOW(), false
FROM projects p WHERE p.deleted=FALSE AND p.status != 'DRAFT'
ON CONFLICT DO NOTHING;

-- ── §14  Parties prenantes ────────────────────────────────────
-- Client-side stakeholders — not application users, so name/job/e-mail/phone
-- are stored as plain text rather than pointing at users(id). influence and
-- interet are the two axes of the stakeholder matrix (V11: FAIBLE/MOYEN/ELEVE).
-- Two contacts per project (a decision-maker and a day-to-day counterpart) so
-- the matrix has points in more than one square.

-- IT director: high influence, high interest.
-- regexp_replace(p.client, '[^a-zA-Z]', '', 'g') strips everything but
-- letters (accents included) to build a fake domain, e.g. "Banque de
-- l'Habitat" -> "banquedelhabitat" -> dsi@banquedelhabitat.tn. Fine here since
-- these are fictional contacts nobody writes to.
-- Phone: '+216 71 ' + a random 6-digit number (71 = Tunis landline prefix);
-- random so it differs on every run, unlike §7's deterministic references.
-- "p.client IS NOT NULL" guards against a NULL client producing a NULL "nom",
-- which is NOT NULL in V11 and would abort the migration.
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

-- Client's functional project manager: medium influence, high interest —
-- deliberately a different square of the matrix. '98' is a mobile prefix
-- (vs '71' landline above), so the two contacts read as different people.
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
-- Change requests: the DEMAND that an avenant (§8) later formalizes into a
-- signed amendment. V11 restricts priorite to
-- FAIBLE/NORMALE/ELEVEE/CRITIQUE and statut to EN_ATTENTE/APPROUVE/REJETE.
-- demandeur_id is the project's manager: the client has no account (§14), so
-- the request always reaches the tool through him.

-- Request 1: extra reporting module. NORMALE, APPROUVE one month after being
-- raised. Eleven projects — overlaps but doesn't exactly match §8's eleven
-- (the two sections were written independently).
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

-- Request 2: framework upgrade forced by the client's security policy. ELEVEE,
-- decided just 10 days after being raised (a security matter isn't left
-- queued). 5 ACTIVE projects.
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

-- Request 3: government SSO switch. CRITIQUE, still EN_ATTENTE — date_decision
-- is deliberately absent from this statement's column list (undecided means no
-- decision date), giving the "waiting for a decision" filter something to show.
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

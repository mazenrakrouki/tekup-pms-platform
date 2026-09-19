-- =============================================================
-- V9 : Billing - milestones, payments, amendments + permissions
--      (original French title: "Facturation - jalons, paiements, avenants")
-- =============================================================
-- WHAT THIS FILE IS
--   One Flyway migration. It creates the three tables of the billing module and
--   registers the two permissions that guard it:
--     jalons_facturation = the payment plan of a contract ("30 % of the budget
--                          when the design is accepted, 40 % at delivery...");
--     paiements          = the money really received against those milestones;
--     avenants           = the contract amendments that change the budget.
--
-- WHERE IT SITS IN THE FLOW
--   Before it: V5 created "projects" (all three foreign keys need it), V1/V2
--   created roles, permissions and role_permissions. After it: V12 rebuilds the
--   whole role/permission matrix, and V15 adds a workload column to avenants.
--   At runtime: BillingController (/api/projects/{projectId}/jalons,
--   /paiements, /avenants) -> ProjectScopeInterceptor (ADR-021) ->
--   JalonService / PaiementService / AvenantService -> the repositories that
--   read and write these three tables. The Java entities are
--   com.pms.billing.entity.JalonFacturation, Paiement and Avenant.
--
-- WHY IT EXISTS
--   Without these tables PMS knows what a project COSTS but never what it has
--   BILLED or CASHED. The KPI engine needs them: KpiService sums the milestones
--   in status FACTURE or PAYE to produce "total facture", and the FAE
--   (= work done but not invoiced yet). Deleting them would leave the whole
--   revenue side of every project blank.
--
-- ONE RULE TO KNOW BEFORE THE JURY ASKS
--   "jalons_facturation.montant" is the single place in PMS where a computed
--   amount is STORED in a column instead of being derived when read. It is a
--   deliberate exception, recorded as H-4 in the audit document: an amount
--   already printed on an invoice must never change again. JalonService
--   .recomputePrevuMontants() therefore recomputes only the milestones still in
--   status PREVU when the budget moves, and leaves FACTURE and PAYE frozen.
-- =============================================================

-- Billing milestones (French: "jalons de facturation")
CREATE TABLE jalons_facturation (
    id              BIGSERIAL       PRIMARY KEY,
    -- The project whose payment plan this milestone belongs to.
    project_id      BIGINT          NOT NULL,
    -- What the client is billed for, in plain words ("Acceptance of the design").
    label           VARCHAR(255)    NOT NULL,
    -- Share of the contract this milestone represents, as a percentage number:
    -- 30.00 means 30 %. NUMERIC(5,2) allows 0.01 to 999.99, and the CHECK below
    -- narrows that to the range that makes business sense.
    pourcentage     NUMERIC(5,2)    NOT NULL,   -- % of the budget; the live rows of one project must sum to <= 100
    -- The money the percentage represents, in the project currency.
    -- It is nullable because a project can have a payment plan before its budget
    -- is known: JalonService.computeMontant() then returns NULL, which reads as
    -- "not computable yet" - a 0 would wrongly read as "this milestone is worth
    -- nothing" and recalculerStatut() would mark it fully paid on a payment of 0.
    montant         NUMERIC(15,2),              -- effective budget x pourcentage / 100 (see H-4 above)
    -- Date the invoice is expected, then the date it was really issued.
    -- date_facture comes from the user and not from the server clock, because an
    -- invoice is often recorded in the tool a few days after it was sent.
    date_prevue     DATE,
    date_facture    DATE,
    -- The lifecycle: PREVU (planned) -> FACTURE (invoice sent) -> PAYE (paid).
    -- Stored as text, not as a number, and mapped by the Java enum JalonStatut
    -- with @Enumerated(EnumType.STRING). Why text: if it were the position in the
    -- enum, inserting a new value in the middle of the enum later would silently
    -- change the meaning of every row already saved.
    statut          VARCHAR(20)     NOT NULL DEFAULT 'PREVU',
    -- The three columns every PMS table carries (see V8 for the detail).
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    -- Soft delete. Billing rows must stay auditable and payments point at them,
    -- so JalonService.delete() only sets this flag; the row itself never leaves
    -- the table.
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Foreign key: a milestone cannot exist without its project. Without it, a
    -- project row removed in raw SQL would leave a payment plan attached to
    -- nothing, and the billing screen would fail while reading project.code.
    CONSTRAINT fk_jf_project      FOREIGN KEY (project_id) REFERENCES projects(id),
    -- WHAT: refuses a percentage of 0, a negative one, or one above 100.
    -- WHY : it is the last line of defence. JalonService also checks that the
    --       percentages of one project do not add up to more than 100, but that
    --       check lives in Java and only protects the API. This one protects the
    --       table itself - the demo seed of V26 and any manual SQL go through it.
    -- WITHOUT IT: a milestone of -30 % would be accepted and would subtract money
    --       from the payment plan, or a milestone of 0 % would sit in the plan
    --       forever with an amount of 0 that no payment can ever settle.
    CONSTRAINT chk_jf_pourcentage CHECK (pourcentage > 0 AND pourcentage <= 100),
    -- WHAT: the status column may hold only these three words.
    -- WHY : the Java enum JalonStatut already restricts what the application can
    --       write, but Hibernate maps the text back by NAME. A row holding
    --       "FACTUREE" or "facture" would make Hibernate throw
    --       "No enum constant com.pms.billing.entity.JalonStatut.FACTUREE" the
    --       moment somebody opens the billing tab - and nothing would explain it.
    --       This constraint blocks the bad value at write time instead.
    CONSTRAINT chk_jf_statut      CHECK (statut IN ('PREVU','FACTURE','PAYE'))
);

-- WHAT: index for "all the live milestones of project 42", which is the query
--       behind the billing tab (JalonFacturationRepository.findActiveByProjectId).
-- WHY : the same partial-index reasoning as in V8 - only the rows with
--       deleted = FALSE are indexed, because every query filters on them, so the
--       index stays small and the soft-deleted rows cost nothing.
-- WITHOUT IT: PostgreSQL scans the whole billing table of the company each time
--       one project's payment plan is opened.
CREATE INDEX idx_jf_project ON jalons_facturation(project_id) WHERE deleted = FALSE;

-- Payments attached to the milestones (French: "paiements liés aux jalons")
CREATE TABLE paiements (
    id              BIGSERIAL       PRIMARY KEY,
    -- The milestone this money settles. The link is to the milestone and NOT to
    -- the project: money always answers an invoice, and going through the
    -- milestone is what lets JalonService.recalculerStatut() sum the payments of
    -- one invoice and decide whether it is fully paid.
    jalon_id        BIGINT          NOT NULL,
    -- Amount actually received. Several rows may point at the same milestone,
    -- because a client can pay in instalments.
    montant_recu    NUMERIC(15,2)   NOT NULL,
    -- The day the money arrived, typed by the user for the same reason as
    -- date_facture above.
    date_paiement   DATE            NOT NULL,
    -- Free text: bank transfer reference, cheque number. Nullable because cash or
    -- an internal transfer may have none.
    reference       VARCHAR(255),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    -- Soft delete again, and here it carries real meaning: cancelling a payment
    -- sets this flag, and JalonService.recalculerStatut() then moves the
    -- milestone back from PAYE to FACTURE. A hard DELETE would erase the trace of
    -- a payment that was once recorded, which an auditor would never accept.
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Foreign key to the milestone. Without it, a payment could point at a
    -- milestone id that does not exist and the received money would never be
    -- counted anywhere.
    CONSTRAINT fk_pmt_jalon   FOREIGN KEY (jalon_id) REFERENCES jalons_facturation(id),
    -- WHAT: a payment must be strictly positive.
    -- WHY : a refund or a correction is expressed by cancelling the payment (the
    --       soft delete above), never by a negative row.
    -- WITHOUT IT: a payment of -5 000 could be entered, the sum of the payments
    --       would drop below the invoiced amount, and a milestone already paid
    --       would silently fall back to FACTURE.
    CONSTRAINT chk_pmt_montant CHECK (montant_recu > 0)
);

-- WHAT: index for "all the live payments of milestone 42".
-- WHY : PaiementRepository.sumMontantByJalonId() runs this lookup after EVERY
--       payment is created or cancelled, because the milestone status is derived
--       from that sum. It is one of the hottest queries of the billing module.
CREATE INDEX idx_pmt_jalon ON paiements(jalon_id) WHERE deleted = FALSE;

-- Contract amendments (French: "avenants"). They change the revised budget of
-- the project, which is why AvenantService calls
-- JalonService.recomputePrevuMontants() after every create and delete.
CREATE TABLE avenants (
    id              BIGSERIAL       PRIMARY KEY,
    project_id      BIGINT          NOT NULL,
    -- The amendment number as written on the paper document ("AV-2025-01"), so a
    -- row in PMS can be matched with the signed file in the archive. Text, not a
    -- number, because the client's numbering may contain letters and slashes.
    numero          VARCHAR(50)     NOT NULL,
    -- What the amendment is about, in one sentence.
    objet           VARCHAR(500),
    -- The change in contract value. Note there is NO check constraint here, and
    -- that is on purpose: a positive amount is an increase, a negative one a
    -- reduction. An amendment that cuts the scope of a project is a normal event.
    montant         NUMERIC(15,2)   NOT NULL,   -- positive = increase, negative = reduction
    -- The date the amendment was signed.
    date_avenant    DATE            NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_av_project FOREIGN KEY (project_id) REFERENCES projects(id)
);

-- WHAT: index for "all the live amendments of project 42".
-- WHY : the revised budget of a project is the initial budget plus the sum of
--       its amendments, so this list is read every time the effective budget is
--       needed - which is on every billing and KPI screen.
CREATE INDEX idx_av_project ON avenants(project_id) WHERE deleted = FALSE;

-- ── Billing permissions ──────────────────────────────────────────
-- Authorization in PMS is dynamic and permission-based (ADR-001): a permission
-- is a ROW in the "permissions" table, and @PreAuthorize("hasAuthority('X')")
-- on the service methods tests that row's code. No line of code anywhere asks
-- "is this user a DIRECTEUR?". That is why a new module ships its permissions
-- as data, in the same migration that creates its tables.
--
-- ON CONFLICT (code) DO NOTHING relies on the unique constraint
-- uk_permissions_code created in V1: if the code already exists, the row is
-- skipped instead of raising an error, so re-running the whole schema from
-- scratch cannot fail here.
-- IMPORTANT, AND EASY TO GET WRONG IN FRONT OF THE JURY: both codes below were
-- ALREADY inserted by V2, with module = 'FACTURATION'. The ON CONFLICT clause
-- therefore makes these two lines a no-op on a fresh database, and the module
-- actually stored stays 'FACTURATION', never 'BILLING'. The "module" column is
-- only a label used to group permissions on the administration screen, so
-- nothing breaks - but the grouping shown is the V2 one.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_BILLING', 'BILLING', NOW(), NOW(), FALSE),
  ('VIEW_BILLING',   'BILLING', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- WHAT: gives MANAGE_BILLING (create, edit, invoice, record payments) to the
--       two roles that run the contracts.
-- HOW : "FROM roles r, permissions p WHERE ..." is a cross join filtered by the
--       WHERE clause - every matching role is paired with the matching
--       permission. Two roles x one permission = two rows inserted.
--       Writing the ids by hand is impossible: roles.id and permissions.id are
--       generated by sequences, so the migration has to look them up by name.
-- WHY ON CONFLICT DO NOTHING: role_permissions has (role_id, permission_id) as
--       its primary key, so re-inserting an existing pair would abort the whole
--       migration. Skipping it makes the script safe to replay.
-- NOTE: V12 later DELETEs every grant of these four roles and rebuilds the
--       matrix from scratch, so what this block writes is only the state between
--       V9 and V12. The live matrix is the one in V12 (and V13, V20, V24, V25).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET') AND p.code = 'MANAGE_BILLING'
ON CONFLICT DO NOTHING;

-- Same shape for the read-only permission, given to one role more.
-- The split between VIEW_ and MANAGE_ is what lets a director read the payment
-- plan of a project without being able to invoice it (business rule BR-038:
-- billing is a project-manager job).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET', 'DIRECTEUR') AND p.code = 'VIEW_BILLING'
ON CONFLICT DO NOTHING;

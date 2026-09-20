-- V9: billing — jalons_facturation (invoicing plan of a contract, e.g. "30%
-- at design acceptance, 40% at delivery..."), paiements (money actually
-- received against milestones), avenants (contract amendments), plus the two
-- permissions that guard this module.
--
-- Without these tables PMS knows what a project COSTS but never what it has
-- BILLED or CASHED: KpiService sums FACTURE/PAYE milestones for total_facture
-- and the FAE (work done but not invoiced).
--
-- "jalons_facturation.montant" is the one place in PMS where a computed
-- amount is STORED rather than derived on read (audit item H-4): an amount
-- already printed on an invoice must never change again.
-- JalonService.recomputePrevuMontants() only recomputes milestones still in
-- status PREVU when the budget moves, leaving FACTURE/PAYE frozen.

CREATE TABLE jalons_facturation (
    id              BIGSERIAL       PRIMARY KEY,
    project_id      BIGINT          NOT NULL,
    label           VARCHAR(255)    NOT NULL,
    -- Percentage of the contract, e.g. 30.00 = 30%.
    pourcentage     NUMERIC(5,2)    NOT NULL,   -- % of the budget; the live rows of one project must sum to <= 100
    -- Nullable: a project can have a payment plan before its budget is known —
    -- JalonService.computeMontant() then returns NULL ("not computable yet"),
    -- since a 0 would misread as "this milestone is worth nothing".
    montant         NUMERIC(15,2),              -- effective budget x pourcentage / 100 (see H-4 above)
    -- date_facture comes from the user, not the server clock: an invoice is
    -- often recorded a few days after it was actually sent.
    date_prevue     DATE,
    date_facture    DATE,
    -- PREVU -> FACTURE -> PAYE. Stored as text (Java enum JalonStatut via
    -- @Enumerated(STRING)) so a reordered enum can't silently change old rows.
    statut          VARCHAR(20)     NOT NULL DEFAULT 'PREVU',
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    -- Soft delete: billing rows must stay auditable and payments point at them.
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_jf_project      FOREIGN KEY (project_id) REFERENCES projects(id),
    -- Last line of defence: JalonService also checks a project's percentages
    -- don't exceed 100 in total, but that only protects the API — this
    -- protects the table itself (the V26 demo seed and any manual SQL too).
    CONSTRAINT chk_jf_pourcentage CHECK (pourcentage > 0 AND pourcentage <= 100),
    -- Mirrors the Java enum: a row written outside Hibernate (e.g. "FACTUREE")
    -- would otherwise throw "No enum constant" the moment the tab is opened.
    CONSTRAINT chk_jf_statut      CHECK (statut IN ('PREVU','FACTURE','PAYE'))
);

-- Backs "all the live milestones of project X" (the billing tab).
CREATE INDEX idx_jf_project ON jalons_facturation(project_id) WHERE deleted = FALSE;

CREATE TABLE paiements (
    id              BIGSERIAL       PRIMARY KEY,
    -- Points at the milestone, not the project: JalonService.recalculerStatut()
    -- sums payments per invoice to decide whether it's fully paid.
    jalon_id        BIGINT          NOT NULL,
    -- Several rows may point at the same milestone (instalments).
    montant_recu    NUMERIC(15,2)   NOT NULL,
    date_paiement   DATE            NOT NULL,
    -- Bank reference / cheque number; nullable (cash has none).
    reference       VARCHAR(255),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    -- Soft delete with real meaning here: cancelling a payment moves the
    -- milestone back from PAYE to FACTURE via recalculerStatut(). A hard
    -- DELETE would erase a payment's trace, which an auditor wouldn't accept.
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_pmt_jalon   FOREIGN KEY (jalon_id) REFERENCES jalons_facturation(id),
    -- A refund/correction is a cancellation (soft delete above), never a
    -- negative row — which would silently drop a milestone back to FACTURE.
    CONSTRAINT chk_pmt_montant CHECK (montant_recu > 0)
);

-- PaiementRepository.sumMontantByJalonId() runs this after every payment
-- create/cancel (the milestone status is derived from that sum) — one of the
-- hottest queries of the billing module.
CREATE INDEX idx_pmt_jalon ON paiements(jalon_id) WHERE deleted = FALSE;

-- Amendments change the revised budget of the project, which is why
-- AvenantService calls JalonService.recomputePrevuMontants() after every create/delete.
CREATE TABLE avenants (
    id              BIGSERIAL       PRIMARY KEY,
    project_id      BIGINT          NOT NULL,
    -- The paper document's number (e.g. "AV-2025-01"), text since the
    -- client's own numbering may include letters/slashes.
    numero          VARCHAR(50)     NOT NULL,
    objet           VARCHAR(500),
    -- No CHECK on purpose: positive = increase, negative = a normal scope cut.
    montant         NUMERIC(15,2)   NOT NULL,   -- positive = increase, negative = reduction
    date_avenant    DATE            NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_av_project FOREIGN KEY (project_id) REFERENCES projects(id)
);

-- Revised budget = initial + sum of amendments, read on every billing/KPI screen.
CREATE INDEX idx_av_project ON avenants(project_id) WHERE deleted = FALSE;

-- ── Billing permissions ──────────────────────────────────────────
-- ADR-001: authorization is data-driven — a permission is a row, and
-- @PreAuthorize("hasAuthority('X')") tests it; nothing checks a role name.
--
-- Both codes were already inserted by V2 with module = 'FACTURATION', so
-- ON CONFLICT makes these two lines a no-op on a fresh database — the stored
-- module stays 'FACTURATION', never 'BILLING'. Cosmetic only (it's just the
-- admin-screen grouping label).
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_BILLING', 'BILLING', NOW(), NOW(), FALSE),
  ('VIEW_BILLING',   'BILLING', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- MANAGE_BILLING (create, edit, invoice, record payments) to the two roles
-- that run contracts. Cross join filtered by WHERE; ids looked up since
-- roles.id/permissions.id come from sequences.
-- NOTE: V12 later DELETEs every grant of these four roles and rebuilds the
-- matrix from scratch — the live matrix is V12's (plus V13, V20, V24, V25).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET') AND p.code = 'MANAGE_BILLING'
ON CONFLICT DO NOTHING;

-- Read-only permission, one role more: VIEW_/MANAGE_ split lets a director
-- read the payment plan without being able to invoice it (BR-038: billing is
-- a project-manager job).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET', 'DIRECTEUR') AND p.code = 'VIEW_BILLING'
ON CONFLICT DO NOTHING;

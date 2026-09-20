-- V23: creates lignes_di, the table behind the Devis Interne (DI, internal quote) - the line-by-line sheet of
-- what was sold vs. what it really costs the company - plus the MANAGE_DI permission guarding it (spec F-AFF-13 §3).
--
-- DECISION 2026-07-05 (BUSINESS_ANALYSIS.md §16): only the STRUCTURE is implemented, as an empty template - the
-- company's real values (salaries, margins) are NEVER seeded. No "total"/"margin" column: amounts and margins
-- are computed at READ time (currency x exchange rate), since a stored total would go stale the moment one line
-- or the exchange rate changes, and a wrong margin here is a wrong price quoted to a client.
--
-- DevisInterneController -> DevisInterneService (hasAuthority('MANAGE_DI'), two-pass calculation) ->
-- LigneDiRepository -> lignes_di. ProjectScopeInterceptor adds a second gate on /api/projects/{id}/** (ADR-021).

-- One row = one line of one project's internal quote. A flat table with a "section" column instead of three
-- tables: all three sections share the same columns and are always read together as one ordered sheet.
CREATE TABLE lignes_di (
    id                  BIGSERIAL     PRIMARY KEY,
    -- Owning project; a line with no project would be unreachable by any screen.
    project_id          BIGINT        NOT NULL,
    -- Sheet block, stored as text (enum SectionDi by name): storing the enum ordinal instead would silently
    -- renumber every row the day a new value is inserted mid-enum.
    section             VARCHAR(20)   NOT NULL,  -- HONORAIRES | FRAIS | AUTRES_FRAIS
    -- Display rank inside the section; business data (the sheet is read top to bottom), not a UI detail.
    ordre               INT           NOT NULL DEFAULT 0,
    -- Contractual profile sold, as written in the offer.
    profil_contractuel  VARCHAR(120),            -- e.g. "PC-1 Chef de mission"
    -- Person proposed in the offer vs. who is really staffed - the gap a project review looks at.
    ressource_proposee  VARCHAR(120),            -- name put forward in the offer
    ressource_retenue   VARCHAR(120),            -- who is really staffed
    -- Billing unit; free text with a default rather than an enum, since sales can invent a new one at any time.
    unite               VARCHAR(20)   NOT NULL DEFAULT 'H-Jour',
    -- What was SOLD: man-days (JH) and unit price, in the project's own currency (converted to TND at read time).
    charge_vendue_jh    NUMERIC(10,2),           -- man-days sold in the contract
    prix_vente_unitaire NUMERIC(15,2),           -- project currency, per day
    -- What it really COSTS: internal man-days expected and the TCC daily cost price (always in TND). The gap
    -- between sold and internal days is where the margin is.
    quantite_interne_jh NUMERIC(10,2),           -- internal man-days estimated
    cout_unitaire_tcc   NUMERIC(10,2),           -- TND per day
    -- Three extra cost buckets from the Excel method this screen reproduces, kept separate since finance
    -- reviews each one individually rather than as one lump "other costs" figure.
    frais_divers        NUMERIC(15,2),           -- FD (miscellaneous expenses)
    frais_generaux      NUMERIC(15,2),           -- FG-P&ST (general overhead)
    cout_impots         NUMERIC(15,2),           -- direct taxes (RS/IS, IRPP/CNSS, ENR, REDEV)
    -- Tax lines only (AUTRES_FRAIS section): a percentage of the whole contract, so DevisInterneService runs in
    -- two passes (totals sold, then applies this rate). Fraction on 4 decimals: 0.05, not 5, for 5%.
    taux_pourcentage    NUMERIC(7,4),            -- tax lines: % of the total sold in TND (e.g. 0.05)
    -- Audit columns from BaseEntity's JPA auditing; on this sensitive table they answer "who changed this cost
    -- price, and when".
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    -- Soft delete: a quote is a financial document, a line must stay explainable after removal.
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE,
    -- No ON DELETE clause: projects are never hard-deleted (they soft-delete too), so a CASCADE would describe
    -- an impossible case - and if it ever happened by hand, would silently destroy the whole quote.
    CONSTRAINT fk_ligne_di_project FOREIGN KEY (project_id) REFERENCES projects(id),
    -- Database-level guard on the section list: without it a value like 'HONORAIRE' (missing S) would be
    -- accepted and crash Hibernate with "No enum constant" the next time the sheet opens.
    CONSTRAINT chk_ligne_di_section CHECK (section IN ('HONORAIRES', 'FRAIS', 'AUTRES_FRAIS'))
);

-- Sheet is always read one project at a time; partial index (deleted = FALSE) keeps it from growing with lines
-- no query ever asks for.
CREATE INDEX idx_ligne_di_project ON lignes_di(project_id) WHERE deleted = FALSE;

-- MANAGE_DI capability: view + edit the internal quote (sensitive data). Given to Directeur only for now;
-- reassignable at any time since authorization is a role_permissions row, never a role name in code.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
    ('MANAGE_DI', 'PROJET', NOW(), NOW(), FALSE)
-- ON CONFLICT guards a replay against uk_permissions_code (V1): without it a duplicate key would abort the
-- whole migration and leave the grant below undone.
ON CONFLICT (code) DO NOTHING;

-- Attach the new capability to the DIRECTEUR role. The join pairs every role with the one MANAGE_DI row (no FK
-- equality involved), narrowed by WHERE to (DIRECTEUR, MANAGE_DI) - ids come from BIGSERIAL and can't be hard-coded.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'MANAGE_DI'
WHERE r.name = 'DIRECTEUR'
-- No conflict target needed: (role_id, permission_id) is the table's primary key, the only possible clash.
ON CONFLICT DO NOTHING;

-- Invalidate Directeur sessions so the new permission is reloaded (ADR-017): otherwise an already-logged-in
-- director keeps the cached authority list without MANAGE_DI and gets 403 on the DI screen until it expires.
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name = 'DIRECTEUR');

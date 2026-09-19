-- =============================================================
-- V23 : Devis Interne — empty structure (spec F-AFF-13 §3)
-- (French original: "Devis Interne — structure vide")
-- -------------------------------------------------------------
-- WHAT THIS FILE IS
-- A Flyway migration (Flyway plays each .sql file of this folder once, in
-- version order, and records that it did). It creates the single table behind
-- the Devis Interne, and it creates the permission that guards that table.
--
-- DI = "Devis Interne", the internal quote: the line-by-line sheet that says,
-- for one project, what was sold to the client and what it really costs the
-- company. It is the most sensitive screen of the application, because it holds
-- cost prices and margins.
--
-- DECISION 2026-07-05 (BUSINESS_ANALYSIS.md §16): only the STRUCTURE of the DI
-- is implemented, as an empty template. The company's real values (salaries,
-- margins) are NEVER seeded — each deployment types in its own. Amounts in TND
-- and margins are computed when the sheet is READ (currency × exchange rate),
-- never stored in a column. So there is no "total" or "margin" column below:
-- that is deliberate, not an omission.
-- Why: a stored total silently becomes false the moment one line or the
-- exchange rate changes, and a wrong margin on this screen is a wrong price
-- given to a client.
--
-- WHERE IT SITS IN THE FLOW
--   HTTP /api/projects/{id}/devis-interne
--     -> DevisInterneController (no security check of its own)
--     -> DevisInterneService  : checks hasAuthority('MANAGE_DI'), runs the
--                               two-pass calculation, opens the transaction
--     -> LigneDiRepository    : reads and writes the rows of lignes_di
--     -> com.pms.project.entity.LigneDi : one Java object = one row here.
--   ADR-021 adds a second gate: ProjectScopeInterceptor sees the URL pattern
--   /api/projects/{id}/** and refuses the call when the caller has no right on
--   THAT project. Holding MANAGE_DI is not enough by itself.
--
-- WHY IT EXISTS
-- Without this file there is no place to store the internal quote at all, and
-- the sold-margin baseline added by V22 (projects.marge_nette_vendue) would
-- stay a number typed by hand with nothing to check it against.
-- =============================================================

-- One row = one line of the internal quote of one project.
-- Why a flat table with a "section" column instead of three tables (fees,
-- expenses, other expenses): the three sections hold exactly the same columns
-- and are always read together, in one ordered list, to build one sheet. Three
-- tables would mean three queries and a UNION on every single read.
CREATE TABLE lignes_di (
    -- BIGSERIAL = 8-byte integer that PostgreSQL fills in by itself, starting at
    -- 1. BIG and not plain SERIAL because a 4-byte counter stops at about 2.1
    -- billion, and the audit tables of this schema all use BIGINT ids.
    id                  BIGSERIAL     PRIMARY KEY,
    -- The project this line belongs to. NOT NULL: a quote line with no project
    -- is nonsense and would never be reachable by any screen.
    project_id          BIGINT        NOT NULL,
    -- Which block of the sheet the line belongs to. Stored as text, not as a
    -- number, and mapped in Java by @Enumerated(EnumType.STRING) on SectionDi.
    -- Why text: a row you can read in psql, and, above all, inserting a new
    -- value in the middle of the Java enum would silently renumber every stored
    -- row if the ordinal were stored — a FRAIS line would become AUTRES_FRAIS
    -- and be taxed as a percentage of the contract.
    section             VARCHAR(20)   NOT NULL,  -- HONORAIRES | FRAIS | AUTRES_FRAIS
    -- Display rank inside the section. The sheet is a document read top to
    -- bottom, so the order is business data, not a detail of the screen.
    -- Without it, the rows would come back in whatever order PostgreSQL finds
    -- them, and the same quote would print differently twice in a row.
    ordre               INT           NOT NULL DEFAULT 0,
    -- Contractual profile sold, as written in the offer.
    profil_contractuel  VARCHAR(120),            -- e.g. "PC-1 Chef de mission"
    -- The person put forward in the offer, and the person really staffed. The
    -- two are kept side by side on purpose: the gap between what was promised
    -- and what was delivered is exactly what a project review looks at.
    ressource_proposee  VARCHAR(120),            -- name put forward in the offer
    ressource_retenue   VARCHAR(120),            -- who is really staffed
    -- Billing unit. Free text with a default rather than an enum, because an
    -- offer can be sold per day, per month or as a flat package, and a CHECK
    -- list would have to be migrated every time the sales team invents one.
    unite               VARCHAR(20)   NOT NULL DEFAULT 'H-Jour',
    -- What was SOLD on this line: man-days (JH = "jour-homme", one person for
    -- one day) and the unit price, in the CURRENCY OF THE PROJECT, not in TND.
    -- The conversion to dinars happens once, at read time, in DevisInterneService.
    charge_vendue_jh    NUMERIC(10,2),           -- man-days sold in the contract
    prix_vente_unitaire NUMERIC(15,2),           -- project currency, per day
    -- What it really COSTS: the man-days the company expects to spend, and the
    -- daily cost price. TCC = "taux de coût complet", the fully loaded cost of
    -- one day of that person (salary plus charges), always expressed in TND.
    -- Internal days can differ from sold days: that gap is where the margin is.
    quantite_interne_jh NUMERIC(10,2),           -- internal man-days estimated
    cout_unitaire_tcc   NUMERIC(10,2),           -- TND per day
    -- The three extra cost buckets of the Excel method this screen reproduces:
    -- FD = miscellaneous expenses, FG-P&ST = general overhead, and direct taxes.
    -- They are separate columns and not one "other costs" total because the
    -- review has to justify each bucket separately to the finance department.
    frais_divers        NUMERIC(15,2),           -- FD (miscellaneous expenses)
    frais_generaux      NUMERIC(15,2),           -- FG-P&ST (general overhead)
    cout_impots         NUMERIC(15,2),           -- direct taxes (RS/IS, IRPP/CNSS, ENR, REDEV)
    -- Only for tax lines of the AUTRES_FRAIS section: the cost of the line is a
    -- percentage of the whole contract, so it cannot be known until every other
    -- line has been added up. This is why DevisInterneService runs in two
    -- passes: pass 1 totals what is sold, pass 2 applies this rate to it.
    -- Stored as a fraction on 4 decimals: 0.05 means 5 %. Writing 5 here instead
    -- of 0.05 would charge the project five times the contract value.
    taux_pourcentage    NUMERIC(7,4),            -- tax lines: % of the total sold in TND (e.g. 0.05)
    -- Audit columns, the same four on every business table of this schema. They
    -- are filled by the JPA auditing of BaseEntity, not by a trigger, so the
    -- e-mail written down is the authenticated caller and not the database user.
    -- On this table in particular they answer the question a jury will ask about
    -- sensitive data: who changed this cost price, and when.
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    -- Soft delete: deleting a line only flips this flag, the row stays.
    -- Why: a quote is a financial document. If a line could disappear for good,
    -- a margin discussed last month could never be explained again.
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE,
    -- The foreign key makes the database refuse a line pointing at a project id
    -- that does not exist. No ON DELETE clause on purpose: projects are never
    -- physically deleted in this schema (they carry the same "deleted" flag), so
    -- a CASCADE would describe a deletion that cannot happen — and if it ever
    -- did happen by hand, CASCADE would silently destroy the whole quote.
    CONSTRAINT fk_ligne_di_project FOREIGN KEY (project_id) REFERENCES projects(id),
    -- The database keeps the list of valid sections itself. Why, when Java
    -- already has the SectionDi enum: a fix applied straight in psql, a data
    -- import, or a future service can bypass Java. Without this CHECK, a row
    -- with section = 'HONORAIRE' (missing S) would be accepted and then crash
    -- Hibernate with "No enum constant" the next time the sheet is opened.
    CONSTRAINT chk_ligne_di_section CHECK (section IN ('HONORAIRES', 'FRAIS', 'AUTRES_FRAIS'))
);

-- The sheet is always read one project at a time ("give me the lines of project
-- 12"), so the index is on project_id.
-- WHERE deleted = FALSE makes it a PARTIAL index: soft-deleted lines are simply
-- not in it. Why that matters: the table keeps every line ever written, and
-- without the filter the index would grow with rows that no query ever asks for,
-- making every read slower for nothing.
CREATE INDEX idx_ligne_di_project ON lignes_di(project_id) WHERE deleted = FALSE;

-- MANAGE_DI capability: view + edit the internal quote (sensitive data).
-- Given to the Directeur only for now; it can be re-assigned at any time through
-- the dynamic RBAC screens, because authorization in this application is a row
-- in role_permissions and never a role name written in the code.
-- Module 'PROJET' is only the grouping used by the admin UI to lay the
-- permission list out in sections.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
    ('MANAGE_DI', 'PROJET', NOW(), NOW(), FALSE)
-- ON CONFLICT (code) DO NOTHING targets the unique constraint on permissions.code
-- (uk_permissions_code, V1): if the code is already there, skip this row instead
-- of failing. Why: it makes the statement replayable. Replayed without it, the
-- whole migration would abort on a duplicate key and leave the grant below
-- undone, so the Directeur would have a table he is not allowed to open.
ON CONFLICT (code) DO NOTHING;

-- Attach the new capability to the DIRECTEUR role.
-- Reading the join: "roles JOIN permissions ON p.code = 'MANAGE_DI'" is not a
-- join on a foreign key — the condition names no column of roles. It pairs every
-- role with that one permission row, and the WHERE then keeps the single pair
-- (DIRECTEUR, MANAGE_DI). The SELECT is used instead of literal ids because the
-- ids are generated by BIGSERIAL and differ from one database to the next;
-- hard-coding "1, 17" here would grant a random permission to a random role on
-- a freshly created database.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'MANAGE_DI'
WHERE r.name = 'DIRECTEUR'
-- No conflict target needed here: the pair (role_id, permission_id) is the
-- primary key of role_permissions, so DO NOTHING covers the only possible clash.
ON CONFLICT DO NOTHING;

-- Invalidate the Directeur sessions so the new set of permissions is reloaded
-- (ADR-017). How it works: every access token carries a "tokenVersion" claim (a
-- claim is one named value written inside the token), and JwtAuthenticationFilter
-- compares it with users.token_version on each request. Adding 1 here makes every
-- token signed before this migration stale, so the next request rebuilds the
-- authorities from the database.
-- Without this line, a Directeur already logged in would keep the authority list
-- that was cached when he logged in — MANAGE_DI missing from it — and would get
-- 403 on the DI screen until his token expired, which looks exactly like a bug.
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name = 'DIRECTEUR');

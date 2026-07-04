-- =====================================================================
-- PMS — PostgreSQL 17 schema  (Phase 3 — Database Design)
-- Source of truth for the data model (ADR-019: Flyway, schema-first).
-- In Phase 5 this file becomes V1__core_schema.sql (+ V2__seed.sql) under
--   backend/src/main/resources/db/migration/
-- Conventions: snake_case plural tables, identity PKs, timestamptz,
--   numeric(18,3)+currency money, months = first-of-month dates,
--   enums = varchar + CHECK, audit + soft delete on business tables.
-- Audit columns created_by / modified_by are plain bigint that LOGICALLY
--   reference users(id) (kept FK-free to avoid bootstrap ordering issues).
-- =====================================================================

-- =========================  Reference / configuration  =========================
create table currencies (
    code    char(3)     primary key,
    name    varchar(60) not null,
    symbol  varchar(8)
);

create table parameters (
    id          bigint generated always as identity primary key,
    param_key   varchar(100) not null unique,
    param_value varchar(255) not null,
    value_type  varchar(20)  not null default 'STRING'
                 check (value_type in ('STRING','INTEGER','DECIMAL','BOOLEAN','DATE')),
    description varchar(255)
);

create table risk_probability_levels (
    id             bigint generated always as identity primary key,
    label          varchar(40)  not null,
    value          numeric(4,2) not null,
    display_client varchar(10)
);

create table risk_severity_levels (
    id          bigint generated always as identity primary key,
    label       varchar(40) not null,
    value       integer     not null,
    max_percent numeric(6,4),
    max_tnd     numeric(18,3)
);

-- =========================  Security / RBAC  =========================
create table roles (
    id          bigint generated always as identity primary key,
    name        varchar(60) not null unique,
    description varchar(255),
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
);

create table permissions (
    id          bigint generated always as identity primary key,
    code        varchar(60) not null unique,
    module      varchar(40) not null,
    description varchar(255)
);

create table users (
    id            bigint generated always as identity primary key,
    first_name    varchar(80)  not null,
    last_name     varchar(80)  not null,
    email         varchar(160) not null unique,
    password_hash varchar(100) not null,
    role_id       bigint       not null references roles(id),
    active        boolean      not null default true,
    first_login   boolean      not null default true,
    token_version integer      not null default 0,   -- bumped to revoke tokens (ADR-017)
    created_by    bigint, created_at  timestamptz not null default now(),
    modified_by   bigint, modified_at timestamptz,
    deleted_at    timestamptz
);

create table role_permissions (
    role_id       bigint not null references roles(id)       on delete cascade,
    permission_id bigint not null references permissions(id) on delete cascade,
    primary key (role_id, permission_id)
);

-- =========================  Resources & cost rates  =========================
create table resources (
    id          bigint generated always as identity primary key,
    full_name   varchar(120) not null,
    profile     varchar(60),
    user_id     bigint unique references users(id),
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
);

create table tcc (
    id                  bigint generated always as identity primary key,
    resource_id         bigint        not null references resources(id),
    year                integer       not null check (year between 2000 and 2100),
    annual_charged_cost numeric(18,3) not null check (annual_charged_cost >= 0),
    currency            char(3)       not null references currencies(code),
    validated           boolean       not null default false,
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz,
    unique (resource_id, year)
);

create table exchange_rates (
    id            bigint generated always as identity primary key,
    from_currency char(3)        not null references currencies(code),
    to_currency   char(3)        not null references currencies(code),
    rate          numeric(18,8)  not null check (rate > 0),
    rate_date     date           not null,
    unique (from_currency, to_currency, rate_date)
);

-- =========================  Projects & organization  =========================
create table projects (
    id              bigint generated always as identity primary key,
    code            varchar(40)  not null unique,
    name            varchar(255) not null,
    description     text,
    client          varchar(160),
    contract_id     varchar(120),
    funder          varchar(160),
    business_model  varchar(40),
    engagement_type varchar(4)   check (engagement_type in ('FP','TM')),
    budget_amount   numeric(18,3) not null check (budget_amount > 0),
    budget_currency char(3)       not null references currencies(code),
    start_date      date not null,
    end_date        date not null,
    status          varchar(20) not null default 'DRAFT'
                     check (status in ('DRAFT','ACTIVE','ON_HOLD','COMPLETED','CANCELLED')),
    director_id     bigint not null references users(id),
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz,
    check (start_date < end_date)
);

create table project_manager_assignments (
    id          bigint generated always as identity primary key,
    project_id  bigint not null references projects(id),
    manager_id  bigint not null references users(id),
    assigned_by bigint references users(id),
    assigned_at timestamptz not null default now(),
    active      boolean not null default true
);
-- exactly one ACTIVE project manager per project (BR-010 / BR-012)
create unique index ux_pma_one_active on project_manager_assignments(project_id) where active;

create table team_assignments (
    id               bigint generated always as identity primary key,
    project_id       bigint not null references projects(id),
    resource_id      bigint not null references resources(id),
    staffing_percent numeric(6,4) check (staffing_percent >= 0),
    staffing_start   date,
    staffing_end     date,
    assigned_by      bigint references users(id),
    assigned_at      timestamptz not null default now(),
    active           boolean not null default true,
    removed_at       timestamptz
);
-- one ACTIVE assignment per (project, developer); history preserved (BR-020)
create unique index ux_team_one_active on team_assignments(project_id, resource_id) where active;

create table stakeholders (
    id           bigint generated always as identity primary key,
    project_id   bigint not null references projects(id),
    category     varchar(30) not null,
    name         varchar(160) not null,
    requirements text,
    impact       text,
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
);

-- =========================  Workload (month-based time series)  =========================
create table workload_plan (
    id          bigint generated always as identity primary key,
    project_id  bigint not null references projects(id),
    resource_id bigint not null references resources(id),
    month       date   not null check (extract(day from month) = 1),  -- first-of-month bucket
    planned_md  numeric(7,2) not null default 0 check (planned_md >= 0),
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz,
    unique (project_id, resource_id, month)
);

create table actual_workload (
    id           bigint generated always as identity primary key,
    project_id   bigint not null references projects(id),
    resource_id  bigint not null references resources(id),
    month        date   not null check (extract(day from month) = 1),
    man_days     numeric(7,2) not null default 0 check (man_days >= 0),
    source       varchar(10) not null default 'MANUAL' check (source in ('MANUAL','KIMAI')),
    accepted     boolean not null default true,   -- the reconciled value used by the KPI engine
    validated    boolean not null default false,
    submitted_by bigint references users(id),
    imported_at  timestamptz,
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz,
    -- both MANUAL and KIMAI values may coexist for the same cell (ADR-003 / D2)
    unique (project_id, resource_id, month, source)
);
-- exactly ONE accepted actual per (project, resource, month) feeds the KPI engine
create unique index ux_actual_one_accepted on actual_workload(project_id, resource_id, month) where accepted;

-- =========================  Billing  =========================
create table billing_milestones (
    id           bigint generated always as identity primary key,
    project_id   bigint not null references projects(id),
    num          integer,
    description  varchar(255),
    percent      numeric(6,4)  not null check (percent >= 0 and percent <= 1),
    amount       numeric(18,3) not null check (amount > 0),
    currency     char(3) not null references currencies(code),
    planned_date date,
    initial_date date,
    actual_date  date,
    status       varchar(12) not null default 'PENDING'
                 check (status in ('PENDING','INVOICED','PAID')),
    invoice_number varchar(40),   -- e.g. "Facture n°24-02" (preserves Excel invoice identity)
    invoice_date   date,
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
    -- NB: Σ(percent) ≤ 100% per project is enforced in the service layer (BR-042)
);

create table payments (
    id           bigint generated always as identity primary key,
    milestone_id bigint not null references billing_milestones(id),
    amount       numeric(18,3) not null check (amount > 0),
    currency     char(3) not null references currencies(code),
    paid_date    date,
    comment      varchar(255),
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
);

create table avenants (
    id                 bigint generated always as identity primary key,
    project_id         bigint not null references projects(id),
    avenant_date       date,
    jh                 numeric(9,2),
    guarantee_jh       numeric(9,2),
    amount             numeric(18,3),
    currency           char(3) references currencies(code),
    amount_tnd         numeric(18,3),
    ppr_tnd            numeric(18,3),
    ppp_tnd            numeric(18,3),
    costs_tnd          numeric(18,3),
    net_margin_tnd     numeric(18,3),
    net_margin_percent numeric(6,4),
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
);

-- =========================  Missions  =========================
create table missions (
    id               bigint generated always as identity primary key,
    project_id       bigint not null references projects(id),
    resource_id      bigint references resources(id),
    phase            varchar(60),
    role_title       varchar(60),
    planned_date     date,
    start_date       date,
    end_date         date,
    beneficiary      varchar(160),
    nights           numeric(6,2)  default 0 check (nights >= 0),
    perdiem_daily    numeric(18,3) default 0,
    perdiem_currency char(3) references currencies(code),
    fx_rate          numeric(18,8) default 1,
    ticket_amount    numeric(18,3) default 0,
    stamp_amount     numeric(18,3) default 0,
    transport_amount numeric(18,3) default 0,
    cost_currency    char(3) references currencies(code),  -- currency of ticket/stamp/transport & of total_cost
    -- Excel: perdiem_DT = nights·perdiem·fx (perdiem_currency→cost_currency) ; total in cost_currency
    -- = perdiem_DT + ticket + stamp + transport  (ticket/stamp/transport assumed already in cost_currency)
    total_cost numeric(18,3) generated always as
        (coalesce(nights,0) * coalesce(perdiem_daily,0) * coalesce(fx_rate,1)
         + coalesce(ticket_amount,0) + coalesce(stamp_amount,0) + coalesce(transport_amount,0)) stored,
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz,
    check (start_date is null or end_date is null or start_date <= end_date)
);

-- =========================  Governance  =========================
create table risks (
    id                 bigint generated always as identity primary key,
    project_id         bigint not null references projects(id),
    code               varchar(40),
    label              varchar(255) not null,
    impact_description text,
    risk_type          varchar(60),
    severity_id        bigint references risk_severity_levels(id),
    probability_id     bigint references risk_probability_levels(id),
    impact_amount_tnd  numeric(18,3),
    weighted_impact    numeric(18,3),   -- probability.value × impact_amount (computed in service)
    treatment          varchar(12) check (treatment in ('REDUCTION','ACCEPTANCE','AVOIDANCE','TRANSFER')),
    measure            text,
    owner              varchar(120),
    status             varchar(20),
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
);

create table deliverables (
    id            bigint generated always as identity primary key,
    project_id    bigint not null references projects(id),
    phase         varchar(60),
    num           varchar(20),
    designation   varchar(255) not null,
    planned_date  date,
    delivered     boolean not null default false,
    delivery_date date,
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
);

create table change_requests (
    id           bigint generated always as identity primary key,
    project_id   bigint not null references projects(id),
    description  text not null,
    requester    varchar(120),
    impact       text,
    validated    boolean,
    responsible  varchar(120),
    due_date     date,
    done         boolean,
    done_date    date,
    effective    boolean,
    closure_date date,
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
);

create table actions (
    id               bigint generated always as identity primary key,
    project_id       bigint not null references projects(id),
    code             varchar(40),
    statement        text not null,
    stakeholder      varchar(120),
    origin           varchar(120),
    action_plan      text,
    owner            varchar(120),
    planned_date     date,
    progress_percent numeric(6,4),
    real_date        date,
    efficacy         varchar(60),
    comment          text,
    created_by  bigint, created_at  timestamptz not null default now(),
    modified_by bigint, modified_at timestamptz,
    active      boolean not null default true,
    deleted_at  timestamptz
);

-- =========================  KPI snapshots (ADR-015)  =========================
create table kpi_snapshots (
    id                       bigint generated always as identity primary key,
    project_id               bigint not null references projects(id),
    snapshot_month           date   not null check (extract(day from snapshot_month) = 1),
    is_current               boolean not null default false,
    computed_at              timestamptz not null default now(),
    currency                 char(3) references currencies(code),  -- reporting currency (TND); all amounts below
    ev_percent               numeric(6,4),
    etc                      numeric(18,3),
    eac                      numeric(18,3),
    actual_labor_cost        numeric(18,3),   -- Σ JH × TCC (labor only)
    actual_other_cost        numeric(18,3),   -- missions / frais (kept separate)
    actual_total_cost        numeric(18,3),   -- labor + other
    forecast_cost            numeric(18,3),
    ca_production            numeric(18,3),
    fae                      numeric(18,3),
    raf                      numeric(9,2),
    derive                   numeric(9,2),
    margin_sold_percent      numeric(6,4),
    margin_current           numeric(18,3),
    margin_current_percent   numeric(6,4),
    margin_eac               numeric(18,3),
    effective_budget         numeric(18,3),   -- initial budget + validated avenants
    budget_remaining         numeric(18,3),   -- effective_budget − EAC
    billing_progress_percent numeric(6,4),
    delivery_percent         numeric(6,4),
    unique (project_id, snapshot_month)
);
-- at most one "current" rollup per project
create unique index ux_kpi_one_current on kpi_snapshots(project_id) where is_current;

-- =========================  Join indexes  =========================
create index ix_users_role          on users(role_id);
create index ix_tcc_resource         on tcc(resource_id);
create index ix_projects_director    on projects(director_id);
create index ix_pma_project          on project_manager_assignments(project_id);
create index ix_team_project         on team_assignments(project_id);
create index ix_team_resource        on team_assignments(resource_id);
create index ix_wplan_project        on workload_plan(project_id);
create index ix_wplan_resource       on workload_plan(resource_id);
create index ix_actual_project       on actual_workload(project_id);
create index ix_actual_resource      on actual_workload(resource_id);
create index ix_wplan_project_month  on workload_plan(project_id, month);
create index ix_actual_project_month on actual_workload(project_id, month);
create index ix_billing_project      on billing_milestones(project_id);
create index ix_payments_milestone   on payments(milestone_id);
create index ix_missions_project     on missions(project_id);
create index ix_risks_project        on risks(project_id);
create index ix_deliverables_project on deliverables(project_id);
create index ix_changes_project      on change_requests(project_id);
create index ix_actions_project      on actions(project_id);
create index ix_avenants_project     on avenants(project_id);
create index ix_stakeholders_project on stakeholders(project_id);
create index ix_kpi_project          on kpi_snapshots(project_id);

-- =====================================================================
-- SEED / reference data  (Phase 5: becomes V2__seed.sql)
-- =====================================================================
insert into currencies(code,name,symbol) values
 ('XAF','Franc CFA (FCFA)','FCFA'), ('TND','Dinar Tunisien','DT'), ('EUR','Euro','€');

insert into parameters(param_key,param_value,value_type,description) values
 ('tcc.overhead','0.70','DECIMAL','Frais généraux (~70%) -> facteur 1.7'),
 ('tcc.workingDays.2024','22','INTEGER','Jours ouvrés / mois 2024'),
 ('tcc.workingDays.2025','20','INTEGER','Jours ouvrés / mois 2025'),
 ('workload.hoursPerDay','8','INTEGER','Heures par jour (heures -> JH)'),
 ('finance.pprPercent','0.05','DECIMAL','Provision pour risques'),
 ('billing.maxTotalPercent','1.00','DECIMAL','Plafond cumulé des jalons'),
 ('reporting.currency','TND','STRING','Devise de restitution');

insert into risk_probability_levels(label,value,display_client) values
 ('Risque éteint',0.00,'Oui'), ('Très peu probable',0.20,'Non'), ('Peu probable',0.40,null),
 ('Probable',0.60,null), ('Très probable',0.80,null), ('Avéré',1.00,null);

insert into risk_severity_levels(label,value,max_percent,max_tnd) values
 ('Mineur',1,0.025,25000), ('Modéré',2,0.05,50000), ('Majeur',3,0.10,100000), ('Critique',4,null,null);

insert into roles(name,description) values
 ('ADMINISTRATOR','Administration de la plateforme'),
 ('DIRECTOR','Gouvernance stratégique'),
 ('PROJECT_MANAGER','Pilotage opérationnel'),
 ('DEVELOPER','Saisie des charges réelles');

insert into permissions(code,module,description) values
 ('MANAGE_USERS','user',null),('CREATE_USER','user',null),('EDIT_USER','user',null),
 ('DEACTIVATE_USER','user',null),('VIEW_USERS','user',null),('RESET_USER_PASSWORD','user',null),
 ('MANAGE_ROLES','role',null),('MANAGE_PERMISSIONS','permission',null),('ASSIGN_ROLE','role',null),
 ('CREATE_PROJECT','project',null),('EDIT_PROJECT','project',null),('DELETE_PROJECT','project',null),
 ('VIEW_PROJECT','project',null),('VIEW_ALL_PROJECTS','project',null),('CHANGE_PROJECT_STATUS','project',null),
 ('ASSIGN_PROJECT_MANAGER','project',null),('MANAGE_PROJECT_BUDGET','project',null),
 ('ASSIGN_DEVELOPER','team',null),('REMOVE_DEVELOPER','team',null),('VIEW_TEAM','team',null),
 ('VIEW_RESOURCE_AVAILABILITY','team',null),
 ('MANAGE_TCC','tcc',null),('VIEW_TCC','tcc',null),
 ('MANAGE_WORKLOAD_PLAN','workload',null),('VIEW_WORKLOAD_PLAN','workload',null),
 ('SUBMIT_ACTUAL_WORKLOAD','actual',null),('VALIDATE_ACTUAL_WORKLOAD','actual',null),
 ('VIEW_ACTUAL_WORKLOAD','actual',null),('IMPORT_KIMAI','actual',null),
 ('MANAGE_BILLING','billing',null),('VIEW_BILLING','billing',null),
 ('MANAGE_MISSIONS','mission',null),('VIEW_MISSIONS','mission',null),
 ('VIEW_FINANCIALS','kpi',null),('VIEW_KPI','kpi',null),('VIEW_EXECUTIVE_DASHBOARD','kpi',null),
 ('MANAGE_RISKS','risk',null),('VIEW_RISKS','risk',null),
 ('MANAGE_DELIVERABLES','deliverable',null),('VIEW_DELIVERABLES','deliverable',null),
 ('MANAGE_STAKEHOLDERS','stakeholder',null),('VIEW_STAKEHOLDERS','stakeholder',null),
 ('MANAGE_CHANGES','change',null),('VIEW_CHANGES','change',null),
 ('MANAGE_AVENANTS','avenant',null),('VIEW_AVENANTS','avenant',null),
 ('VIEW_REPORTS','reporting',null),('EXPORT_REPORTS','reporting',null),
 ('MANAGE_PARAMETERS','config',null);

-- Default Role -> Permission matrix (D4: ASSIGN_DEVELOPER granted to DIRECTOR *and* PROJECT_MANAGER)
insert into role_permissions(role_id,permission_id)
 select r.id, p.id from roles r join permissions p on p.code in
  ('MANAGE_USERS','CREATE_USER','EDIT_USER','DEACTIVATE_USER','VIEW_USERS','RESET_USER_PASSWORD',
   'MANAGE_ROLES','MANAGE_PERMISSIONS','ASSIGN_ROLE','MANAGE_TCC','VIEW_TCC','MANAGE_PARAMETERS')
 where r.name='ADMINISTRATOR';

insert into role_permissions(role_id,permission_id)
 select r.id, p.id from roles r join permissions p on p.code in
  ('CREATE_PROJECT','EDIT_PROJECT','DELETE_PROJECT','VIEW_PROJECT','VIEW_ALL_PROJECTS',
   'CHANGE_PROJECT_STATUS','ASSIGN_PROJECT_MANAGER','MANAGE_PROJECT_BUDGET',
   'ASSIGN_DEVELOPER','REMOVE_DEVELOPER','VIEW_TEAM','VIEW_RESOURCE_AVAILABILITY',
   'VIEW_WORKLOAD_PLAN','VIEW_ACTUAL_WORKLOAD','VALIDATE_ACTUAL_WORKLOAD','VIEW_BILLING','VIEW_MISSIONS',
   'VIEW_FINANCIALS','VIEW_KPI','VIEW_EXECUTIVE_DASHBOARD','VIEW_TCC',
   'VIEW_RISKS','VIEW_DELIVERABLES','VIEW_STAKEHOLDERS','VIEW_CHANGES','VIEW_AVENANTS',
   'VIEW_REPORTS','EXPORT_REPORTS')
 where r.name='DIRECTOR';

insert into role_permissions(role_id,permission_id)
 select r.id, p.id from roles r join permissions p on p.code in
  ('VIEW_PROJECT','ASSIGN_DEVELOPER','REMOVE_DEVELOPER','VIEW_TEAM','VIEW_RESOURCE_AVAILABILITY',
   'MANAGE_WORKLOAD_PLAN','VIEW_WORKLOAD_PLAN','VALIDATE_ACTUAL_WORKLOAD','VIEW_ACTUAL_WORKLOAD','IMPORT_KIMAI',
   'MANAGE_BILLING','VIEW_BILLING','MANAGE_MISSIONS','VIEW_MISSIONS',
   'VIEW_FINANCIALS','VIEW_KPI','VIEW_TCC',
   'MANAGE_RISKS','VIEW_RISKS','MANAGE_DELIVERABLES','VIEW_DELIVERABLES',
   'MANAGE_STAKEHOLDERS','VIEW_STAKEHOLDERS','MANAGE_CHANGES','VIEW_CHANGES',
   'MANAGE_AVENANTS','VIEW_AVENANTS','VIEW_REPORTS','EXPORT_REPORTS')
 where r.name='PROJECT_MANAGER';

insert into role_permissions(role_id,permission_id)
 select r.id, p.id from roles r join permissions p on p.code in
  ('VIEW_PROJECT','SUBMIT_ACTUAL_WORKLOAD','VIEW_ACTUAL_WORKLOAD','VIEW_MISSIONS','VIEW_WORKLOAD_PLAN')
 where r.name='DEVELOPER';

-- Bootstrap administrator. NB: replace the placeholder hash with a real BCrypt hash in Phase 5.
-- Temp password (to be generated for real): e.g. 'Temp@5824'  -> first_login forces a change.
insert into users(first_name,last_name,email,password_hash,role_id,active,first_login)
 select 'System','Administrator','admin@pms.local','$2a$10$REPLACE_WITH_REAL_BCRYPT_HASH', r.id, true, true
 from roles r where r.name='ADMINISTRATOR';

-- =====================================================================
-- End of schema. ~26 tables, dynamic RBAC, month-based time series,
-- KpiSnapshot history, multi-currency, configurable parameters,
-- audit + soft delete. See docs/DATABASE_DESIGN.md for the narrative.
-- =====================================================================

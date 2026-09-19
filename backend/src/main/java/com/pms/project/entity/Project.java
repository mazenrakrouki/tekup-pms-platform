package com.pms.project.entity;

import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * WHAT THIS FILE IS
 * One row of the table projects: the identity card of a project. It holds what the contract
 * says (client, dates, budget, currency, workload sold) and who is in charge of it. It is the
 * central row of the whole application - timesheets, invoicing milestones, KPI snapshots,
 * teams and the internal quote all hang off it.
 *
 * WHERE IT SITS IN THE FLOW
 * ProjectController, on /api/projects
 *   -> ProjectService (create, update, assignChefProjet, changeStatus, archive, delete)
 *   -> ProjectRepository reads and writes this object as one row;
 *   -> ProjectMapper turns it into a ProjectResponse, and ProjectService then strips every
 *      amount from that response for a user without VIEW_KPI (rule BR-050).
 * Read by many other modules: AvenantService and JalonService for invoicing, KpiService for
 * the indicators, DevisInterneService for the currency and the exchange rate,
 * ProjectScopeService for who may see what. The two seeders build these objects to fill a
 * demo database.
 * This class calls nothing itself, apart from its own small derived getters at the bottom.
 *
 * WHY IT EXISTS
 * Without it there is no project, so there is nothing to attach a day of work, an invoice or
 * an indicator to. Less obviously, it is also the place where the money rules of the company
 * are anchored: the currency and the exchange rate of the project live here, so every other
 * module converts amounts the same way instead of each one inventing its own rate.
 *
 * WHERE THE COLUMNS COME FROM
 * Flyway built this table in four steps, and the blocks below follow that history: V5 for the
 * base (code, name, status, dates, budgets, the two people in charge), V14 for the
 * "Fiche d'identification" block copied from the company's Excel sheet, V16 for the archived
 * flag, V22 for margeNetteVendue. JPA never creates or changes the table: application.yml
 * sets ddl-auto to "validate" (ADR-019), so every length, precision and scale written below
 * is checked against the real column at startup and a mismatch stops the application at once.
 *
 * WHAT THE DATABASE GUARANTEES ON ITS OWN
 * Four rules are enforced by PostgreSQL and not by Java, so they hold even if a row is
 * inserted by hand: uk_projects_code, a UNIQUE index on code limited to rows where
 * deleted = false (V18), so a code can be used again after a project is soft-deleted;
 * chk_status, which accepts only the five names of ProjectStatus; chk_initial_budget, which
 * refuses a negative initial budget; and chk_project_dates, which since V17 requires
 * end_date to be greater than OR EQUAL TO start_date, so a one-day project is legal.
 *
 * WHY THE CLASS HAS THIS SHAPE
 * It carries data, plus four tiny derived getters at the bottom that answer questions the
 * screens ask about this row alone. Anything that needs other tables - real costs, progress,
 * invoiced amounts - is deliberately NOT here: those formulas live in KpiService, JalonService
 * and DevisInterneService, so there is one implementation of each and they cannot drift apart.
 *
 * extends BaseEntity adds the columns shared by every table in PMS: id, created_at,
 * updated_at, created_by, updated_by and the "deleted" flag. Why: PMS never really erases a
 * project, it sets deleted = true and every query filters on deleted = false. Without that,
 * deleting a project would orphan years of timesheets and invoices.
 * Careful with the three flags that look alike: "deleted" hides the project everywhere,
 * "archived" (below) only moves a finished project out of the active lists, and the status
 * CANCELLED says the work was stopped while keeping the project fully visible.
 *
 * ANNOTATIONS ON THE CLASS BELOW
 * The @Entity annotation tells Hibernate this class is a database table and not a plain Java
 * object. Why: Spring Data needs it to build ProjectRepository, and every other entity that
 * points at a project needs it too. Without it the application refuses to start with
 * "Not a managed type: com.pms.project.entity.Project".
 *
 * The @Table(name = "projects") annotation pins the exact table name. Why: from the class
 * name Hibernate would guess "project" (singular) while V5 created "projects". Without this
 * line the application starts and then fails on the first query with: relation "project"
 * does not exist.
 *
 * The @Getter and @Setter annotations ask Lombok to write the get.../set... methods at
 * compile time. Why: Hibernate fills the row through them, ProjectService writes every field
 * of the identity block through them, and MapStruct reads them to build ProjectResponse.
 *
 * The @NoArgsConstructor annotation gives an empty constructor. Why: to load a row Hibernate
 * first does "new Project()" and only then fills the fields. Without it, listing the projects
 * fails with InstantiationException.
 *
 * The @AllArgsConstructor and @Builder annotations together give Project.builder().code(...)
 * .name(...).build(), which is how ProjectService.create writes it. Why the builder rather
 * than the long constructor: this class has twenty-three fields and many of them are BigDecimal
 * or String. A constructor with that many arguments would still compile with "client" and
 * "funder" swapped, or with the initial budget put in the revised budget slot, and the wrong
 * project sheet would be saved with no error at all.
 * Note that this is @Builder and not @SuperBuilder, so the builder only knows the fields
 * declared in this class; id, the audit columns and "deleted" come from BaseEntity and from
 * Spring auditing.
 */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project extends BaseEntity {

    /**
     * The short business key of the project, for example "PRJ-2025-014". This is the word
     * people use in meetings and on invoices, so the application treats it as an identity, not
     * as a label: ProjectRepository can find a project by it, and ProjectService refuses a
     * code already taken by an active project.
     *
     * WHY A CODE WHEN THE ROW ALREADY HAS AN id: the id is an internal number that means
     * nothing to a director. The code is the one the company already used on paper before PMS
     * existed, and it must stay stable and readable.
     *
     * nullable = false says a project can never exist without a code. Without it a project
     * could be saved with no code, and it would then be impossible to name in a report.
     * length = 20 mirrors VARCHAR(20) in V5.
     *
     * The uniqueness is NOT declared here. It is a partial unique index in the database,
     * uk_projects_code ... WHERE deleted = FALSE (V18), and ProjectService also checks it
     * first with existsByCodeAndDeletedFalse so the user gets a clear message instead of a
     * database error. Why partial and not a plain UNIQUE constraint: PMS soft-deletes, so a
     * plain constraint would keep the code of a deleted project reserved for ever and the
     * company could never reuse "PRJ-2025-014" after cancelling it by mistake.
     * ProjectService also puts the code in upper case before saving, so "prj-1" and "PRJ-1"
     * cannot both slip past that check.
     */
    @Column(nullable = false, length = 20)
    private String code;

    /** The readable title of the project. Required, and long enough for a real contract title. */
    @Column(nullable = false, length = 255)
    private String name;

    /**
     * Free description of the project.
     *
     * columnDefinition = "TEXT" tells Hibernate the column is PostgreSQL TEXT, with no length
     * limit, instead of the VARCHAR(255) it would use by default. Why: a project description
     * copied from a call for tenders easily runs to several paragraphs. Without this line,
     * startup validation would fail against the TEXT column created by V5, and if the column
     * had been a VARCHAR the description would come back truncated in the middle of a
     * sentence.
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Where the project stands in its life cycle. The rules about which value may follow which
     * live in ProjectStatus itself, and ProjectService.changeStatus asks that enum before
     * writing here.
     *
     * The @Enumerated(EnumType.STRING) annotation stores the NAME, "ACTIVE", instead of the
     * position of the value in the enum. Why: the default, ORDINAL, would store 0 to 4, and
     * inserting a new status in the middle of ProjectStatus one day would silently turn every
     * paused project into a cancelled one. Storing the name also lets the V5 constraint
     * chk_status check the value, and makes the table readable by a human.
     *
     * nullable = false plus @Builder.Default: the column is NOT NULL with a default of 'DRAFT'
     * in V5. Lombok normally drops the "= ProjectStatus.DRAFT" written here when a project is
     * built through Project.builder(), which would send null and break the insert. Adding
     * @Builder.Default keeps it, so a project created without an explicit status starts as a
     * draft - which is what the business wants: a project sheet is prepared before it starts.
     * length = 30 mirrors VARCHAR(30), wide enough for the longest name, CANCELLED.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.DRAFT;

    /**
     * Contractual start date. LocalDate is a date with no time and no time zone, which is what
     * a contract talks about: a project starts on the 15th of January, not at 00:00 UTC.
     * Using a timestamp instead would make the same project look as if it started on the 14th
     * for anyone reading it from another time zone.
     * Allowed to be null: a project sheet is often opened before the dates are agreed.
     * The name = "start_date" part maps the camelCase field onto the snake_case column of V5;
     * without it Hibernate would look for "startdate" and startup validation would fail.
     */
    @Column(name = "start_date")
    private LocalDate startDate;

    /**
     * Contractual end date. The database refuses an end date before the start date through
     * chk_project_dates. Note the history kept in V17: the first version of that constraint
     * used a strict "greater than", which made a one-day project impossible and answered a
     * legitimate save with a 500 error. It now accepts "greater than or equal", which matches
     * the Excel model where both ends of the period are counted - the same reason
     * getDurationDays() below adds 1.
     */
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * The budget signed at the start, in the project currency. Never modified afterwards: it
     * is the reference everything is compared against.
     *
     * BigDecimal, not double: BigDecimal keeps decimal numbers exactly, digit by digit, while
     * double stores them in binary and cannot hold 0.1 exactly, so sums of doubles drift by
     * fractions of a millime. On a budget compared line by line with a signed contract, a
     * one-millime difference is enough to make the whole screen look wrong.
     * precision = 15, scale = 2 mirrors NUMERIC(15,2) in V5: fifteen digits in all, two after
     * the decimal point. Since ddl-auto is "validate", narrowing the column without changing
     * this line would stop the application at startup instead of rounding money in silence.
     * The database also refuses a negative value through chk_initial_budget.
     */
    @Column(name = "initial_budget", precision = 15, scale = 2)
    private BigDecimal initialBudget;

    /**
     * The budget as it stands today, after amendments ("avenants"). AvenantService writes it;
     * it stays null while no amendment has ever been approved.
     *
     * WHY A SECOND COLUMN INSTEAD OF SIMPLY CHANGING initialBudget: the two figures answer two
     * different questions that are both asked at a review - what did we sign, and what are we
     * working with now. Overwriting the first one would erase the evidence of how far the
     * project has moved from its original contract, and an amendment could no longer be
     * justified against anything.
     * Because two columns can disagree, no caller should read this field directly: they call
     * getEffectiveBudget() below, which picks the right one.
     */
    @Column(name = "revised_budget", precision = 15, scale = 2)
    private BigDecimal revisedBudget;

    // ── Identity card block ("Fiche d'identification", the Excel model) ──────────
    // Everything from here down to margeNetteVendue was added by Flyway V14 and V22 to make
    // the project row carry the same information as the Excel sheet the company already
    // fills in. ProjectService.applyFicheIdentification() writes this whole block in one go.
    // All of these fields are allowed to be null: the sheet is filled in progressively, and
    // forcing them at creation time would stop a user opening a project before the contract
    // paperwork is complete.

    /**
     * The contract reference given by the client, kept as text because it follows the
     * client's own numbering and may contain letters, slashes and spaces. It is what lets a
     * project in PMS be matched with a paper file in the archive.
     */
    @Column(name = "contract_id", length = 100)
    private String contractId;

    /** Name of the client who signed the contract. */
    @Column(length = 255)
    private String client;

    /**
     * The organisation actually paying, "bailleur de fonds" on the Excel sheet - typically a
     * development bank on a public project.
     * WHY IT IS KEPT APART FROM client: on publicly funded work the client (a ministry) and
     * the payer (the World Bank, AFD, GIZ) are different bodies with different reporting
     * rules. Merging the two would make it impossible to list all the projects financed by
     * one donor. It stays null on ordinary private contracts.
     */
    @Column(length = 255)
    private String funder;                 // Funder / donor

    /**
     * Whether the company runs this project alone or inside a consortium. See BusinessModel
     * for the two values. Stored as text (EnumType.STRING) rather than as a number, so that
     * a value added to the enum later cannot change the meaning of rows already saved.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "business_model", length = 20)
    private BusinessModel businessModel;   // SEUL | GROUPEMENT

    /**
     * Fixed price or time and materials. See EngagementType for the two values. Stored as
     * text for the same reason as the field above.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "engagement_type", length = 20)
    private EngagementType engagementType; // FORFAIT | REGIE

    /**
     * The currency the contract is written in, as a short code: TND, EUR, FCFA. Every amount
     * on this project - the budget, and every selling price in the internal quote - is
     * expressed in THIS currency, never in TND, unless the field name says tnd.
     *
     * nullable is not set, but the column is NOT NULL with a default of 'TND' in V14, and
     * @Builder.Default keeps the "TND" written here for projects built through
     * Project.builder(). Without it Lombok would drop the initial value and the insert would
     * fail against the NOT NULL column. ProjectService also upper-cases whatever the user
     * types, so "eur" and "EUR" cannot become two different currencies.
     */
    @Column(length = 10)
    @Builder.Default
    private String currency = "TND";       // Project currency (FCFA, TND, EUR...)

    /**
     * How many TND one unit of the project currency is worth. The whole application converts
     * with this single number: amount in TND = amount in currency x this rate.
     *
     * WHY THE RATE IS STORED ON THE PROJECT AND NOT FETCHED FROM A RATE FEED: the company
     * agrees one rate for the life of a contract and uses it in all its own reporting, exactly
     * as the Excel sheet does. A live rate would make yesterday's margin different from
     * today's on a project where nothing happened, and no two reports would ever agree.
     *
     * precision = 15, scale = 6 mirrors NUMERIC(15,6): six digits after the point. Why so
     * many: 1 FCFA is worth roughly 0.005 TND, so with the usual two decimals the rate would
     * round to 0.01 and every FCFA amount would come out about twice too big.
     *
     * The default of 1 matters: a project in TND needs no conversion, and multiplying by 1
     * lets the same formula run for every project instead of having a special case. As above,
     * the column is NOT NULL with a default of 1 in V14, and @Builder.Default is what keeps
     * BigDecimal.ONE when the builder is used - without it the field would arrive null and
     * getBudgetTnd() would have to guess.
     */
    @Column(name = "exchange_rate_to_tnd", precision = 15, scale = 6)
    @Builder.Default
    private BigDecimal exchangeRateToTnd = BigDecimal.ONE;

    /**
     * The share of the budget that goes on software licences and subcontracting, in the
     * project currency. It is money the company passes straight on to someone else, so it is
     * tracked apart from the days worked in-house.
     * Note that ProjectResponse.withoutFinancials() blanks this field: it is financial
     * information, hidden from a user without VIEW_KPI (rule BR-050).
     */
    @Column(name = "license_subcontract_budget", precision = 15, scale = 2)
    private BigDecimal licenseSubcontractBudget;

    /**
     * The total workload sold to the client, in man-days (JH = homme-jour, one person for one
     * day). It is the volume side of the contract, where the budget is the money side.
     * KpiService compares it with the days really booked to show the drift.
     * Note that this field stays visible to a user without VIEW_KPI: BR-050 hides money, and
     * a number of days is not money.
     */
    @Column(name = "sold_workload_days", precision = 10, scale = 2)
    private BigDecimal soldWorkloadDays;   // Sold workload (man-days)

    /**
     * Man-days set aside for the warranty period, after delivery. Kept apart from
     * soldWorkloadDays above because those days are owed to the client but are not part of
     * the work being delivered: mixing them in would make a project look as if it still had
     * budget left to build with when that budget is really reserved for fixing.
     */
    @Column(name = "warranty_workload_days", precision = 10, scale = 2)
    private BigDecimal warrantyWorkloadDays; // Warranty workload (man-days)

    /**
     * Money set aside for contractual penalties, noted PPP on the Excel sheet - what the
     * company expects to lose if it delivers late. Financial, so withoutFinancials() blanks
     * it (BR-050).
     */
    @Column(name = "penalty_provision", precision = 15, scale = 2)
    private BigDecimal penaltyProvision;   // PPP

    /**
     * The net margin the project was SOLD at, as a coefficient: 0.4412 means 44.12 %.
     * Translated from the original French note: commercial baseline, and the margin computed
     * from the internal quote wins over it whenever a quote exists.
     *
     * READ THIS CAREFULLY, IT IS A COMMON MISREADING: this column is a figure typed by a
     * human, kept only as a fallback. KpiService asks
     * DevisInterneService.computeMargeVenduePct(projectId) first and uses THIS value only when
     * that comes back empty, which happens when the project has no internal quote line at all.
     * So the computed margin always wins, and this stays the baseline for projects that were
     * never quoted line by line in PMS.
     *
     * Stored as 0.4412 and not as 44.12 because every rate in the application is a
     * coefficient, ready to multiply. ProjectRequest guards the range between -1 and 1, which
     * also allows a negative value: a project sold at a loss is a real case and must be
     * recordable.
     * precision = 7, scale = 4 mirrors NUMERIC(7,4) from V22: four digits after the point, so
     * 44.12 % is kept exactly rather than rounded to 44 %.
     */
    @Column(name = "marge_nette_vendue", precision = 7, scale = 4)
    private BigDecimal margeNetteVendue;   // Sold baseline (e.g. 0.4412 = 44.12%); the computed DI margin wins when one exists

    /**
     * Archived flag: the project is finished and has been moved out of the working lists, but
     * is still fully readable under "archived projects".
     *
     * THREE THINGS THAT LOOK ALIKE AND ARE NOT. "deleted", from BaseEntity, hides the project
     * everywhere. The status CANCELLED says the work was stopped. This flag says nothing about
     * the work: it is only about tidying the screen. ProjectService.archive() refuses to set
     * it unless the status is already COMPLETED, so archiving can never be used to make a
     * running project disappear.
     * Why a flag and not a sixth status: a project can be un-archived and re-archived freely
     * without touching its life cycle. As a status it would need transition rules of its own,
     * and every query that asks "is this project finished?" would have to test two values.
     *
     * A primitive boolean, not a Boolean object: the column is NOT NULL, and a primitive
     * cannot be null, so the question "is it archived?" always has an answer.
     * Adding @Builder.Default keeps the "false" for projects built through Project.builder(),
     * so a new project starts in the active lists. ProjectRepository.findAllActive filters on
     * archived = false and findAllArchived on archived = true, so this one field decides
     * which of the two lists a project appears in.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean archived = false;      // archived project (finished, out of the active lists)

    /**
     * The director responsible for the project, above the project manager. ProjectMapper reads
     * it to fill directorId and directorName on the response.
     *
     * The @ManyToOne annotation says many projects can point at one user. A real relation and
     * not a plain userId number, so the mapper can print the person's full name without a
     * second lookup on every row of the list.
     *
     * fetch = FetchType.LAZY means the user row is NOT read until someone calls getDirector().
     * Why: the portfolio screen lists many projects at once. With the default EAGER, listing
     * 60 projects would fire one query for the projects plus two more per project, one for the
     * director and one for the project manager - 121 round trips instead of 1, the classic
     * "N+1 queries" problem. This is exactly why every read in ProjectRepository is written
     * with "LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet": those two people are
     * needed on every screen, so they are brought back inside the one query. LEFT and not
     * INNER, because both columns may be empty and an INNER join would silently drop every
     * project that has no director yet.
     * The JOIN FETCH is not optional comfort: application.yml sets open-in-view to false, so
     * a lazy field touched after the transaction has closed throws LazyInitializationException
     * instead of loading quietly.
     *
     * The @JoinColumn annotation names the column, director_id, added by V5 together with the
     * foreign key fk_projects_director to users(id). Nothing says nullable = false here: a
     * project sheet can be opened before a director is named.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "director_id")
    private User director;

    /**
     * The project manager ("chef de projet"), the person who runs the project day to day.
     *
     * THIS FIELD IS PART OF THE SECURITY MODEL, NOT ONLY OF THE PROJECT SHEET.
     * ProjectRepository.findAccessibleProjectIdsByEmail matches on chefProjet.email, so being
     * named here is one of the two ways a user is allowed to open a project at all; the other
     * is being an active member of its team. ProjectScopeService uses that list, and
     * ProjectScopeInterceptor applies it to every URL under /api/projects/{id}/** (ADR-021).
     * So changing this field changes who can see the project - which is why ProjectService
     * guards it with its own permission, ASSIGN_CHEF_PROJET, instead of letting anyone holding
     * EDIT_PROJECT reassign it and hand themselves access.
     *
     * Lazy loading and the join column work exactly as for director above; the column is
     * chef_projet_id with the foreign key fk_projects_chef, and it may be empty while nobody
     * has been appointed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chef_projet_id")
    private User chefProjet;

    /*
     * ── Derived values (computed on every read, never stored) ────────────────────
     * The four methods below are the only logic in this class. None of them has a column:
     * they are recomputed each time they are called.
     *
     * WHY THEY ARE COMPUTED AND NOT SAVED: each one is built from fields of this same row. A
     * saved copy would be a second version of the same truth, and it would go stale the moment
     * an amendment changes the budget or the exchange rate is corrected - and nobody would be
     * able to tell which of the two numbers on the screen is the right one. This is the rule
     * the internal quote follows as well: a computed amount is derived when read, never kept
     * in a column.
     *
     * HOW THEY REACH THE SCREEN: MapStruct matches a method get X () with the field x of
     * ProjectResponse, so durationDays, budgetTnd and pprTnd are filled automatically, and
     * ProjectMapper spells out effectiveBudget explicitly. ProjectService then calls
     * withoutFinancials() for a user without VIEW_KPI, which blanks budgetTnd and pprTnd
     * while keeping durationDays - a length in days is not financial information (BR-050).
     */

    /**
     * The budget in force today: the revised budget when there is one, otherwise the budget
     * signed at the start. Returns null when neither has been filled in.
     *
     * WHY EVERY CALLER MUST USE THIS AND NOT READ THE TWO FIELDS ITSELF: AvenantService and
     * JalonService both compute against the budget. If one of them read initialBudget
     * directly, an amendment that raised the budget would leave the invoicing milestones
     * computed on the old amount, and the total invoiced would never add up to the contract.
     * One method, one answer, used everywhere.
     *
     * The "? :" is Java's short if-then-else: if revisedBudget is not null, take it, otherwise
     * take initialBudget. The null test is what makes "no amendment yet" fall back to the
     * signed budget instead of returning nothing.
     */
    public BigDecimal getEffectiveBudget() {
        return revisedBudget != null ? revisedBudget : initialBudget;
    }

    /**
     * Length of the contract in days, both ends counted, as on the Excel sheet. Returns null
     * when either date is missing, rather than 0: an unknown length and a length of zero are
     * different answers, and returning 0 would print "0 days" on a project whose dates have
     * simply not been agreed yet.
     *
     * ChronoUnit.DAYS.between(a, b) counts the full days from a to b, so for the 1st to the
     * 3rd it returns 2. The "+ 1" turns that into 3, which is what a contract means by "from
     * the 1st to the 3rd". Without it every project would be reported one day short, and a
     * project starting and ending on the same day would show a length of 0 - which is also
     * why the database constraint was relaxed in V17 to allow start and end on the same date.
     */
    public Long getDurationDays() {
        if (startDate == null || endDate == null) return null;
        return ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /**
     * The effective budget converted into TND: budget x the project exchange rate.
     * Returns null when there is no budget at all, so the screen shows an empty box instead of
     * a misleading zero.
     *
     * Why this exists: budgets are held in the client's currency, while everything the company
     * spends is in TND. KpiService needs the TND figure to compute the produced revenue
     * (budget in TND x the earned value percentage), and comparing a budget in FCFA with costs
     * in TND would produce a margin that is wrong by a factor of two hundred.
     *
     * The rate is defended twice: the column is NOT NULL with a default of 1, and the local
     * variable still falls back to BigDecimal.ONE when the field is null. Why the belt and
     * braces: an object built in memory and not yet loaded from the database can have a null
     * rate, and multiply() on null would end the KPI request with a NullPointerException
     * instead of showing the figures. Falling back to 1 means "no conversion", which is the
     * right answer for a project already in TND.
     */
    public BigDecimal getBudgetTnd() {
        BigDecimal eff = getEffectiveBudget();
        if (eff == null) return null;
        BigDecimal rate = exchangeRateToTnd != null ? exchangeRateToTnd : BigDecimal.ONE;
        return eff.multiply(rate);
    }

    /**
     * "Provision Pour Risques" - the money set aside for risk on this project: 5 % of the
     * budget in TND, as the Excel identity sheet computes it. Returns null when there is no
     * budget, because 5 % of nothing is not a meaningful figure to show.
     *
     * Note the difference with penaltyProvision above: that one is a number typed by a user
     * for late-delivery penalties, this one is a fixed percentage of the budget.
     *
     * The rate is written as new BigDecimal("0.05"), from a String and not from the number
     * 0.05. Why: new BigDecimal(0.05) with a double would store
     * 0.05000000000000000277555756156289135105907917022705078125, because 0.05 has no exact
     * binary form, and the provision of a large project would end a few millimes off the
     * Excel sheet it is checked against. Building it from text keeps exactly 0.05.
     */
    public BigDecimal getPprTnd() {
        BigDecimal tnd = getBudgetTnd();
        return tnd != null ? tnd.multiply(new BigDecimal("0.05")) : null;
    }
}

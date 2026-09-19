package com.pms.kpi.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * WHAT THIS FILE IS
 * One row of the database table snapshot_kpis: a frozen photo of one project's money and
 * progress figures, taken on one date. "Snapshot" means the numbers are copied into
 * columns and never recomputed, so the monthly project review can be opened again next
 * year and still show exactly the figures that were discussed that day.
 *
 * WHERE IT SITS IN THE FLOW
 * KpiController (GET and POST /api/projects/{projectId}/kpi/snapshots)
 *   -> KpiService.createSnapshot() computes the figures from the live tables
 *      (plan_charges, charges_reelles, livrables, jalons_facturation, resources, tcc_annuels)
 *      and copies them into a SnapshotKpi built with the Lombok builder below;
 *   -> SnapshotKpiRepository.save() writes this object as one row;
 *   -> SnapshotKpiMapper turns the row back into a KpiResponse sent to the browser.
 * DemoDataSeeder and EnterpriseDataSeeder also build these objects to fill a demo database.
 * This class calls nothing itself: it only describes the table to Hibernate.
 *
 * WHY IT EXISTS
 * Everywhere else in PMS an amount is recomputed every time it is read and is never kept in
 * a column - that is the firm rule for the Devis Interne (internal quote). This table is the
 * one deliberate exception, and it is not a cache: the live KPI screen
 * (KpiService.computeLive) recomputes everything from the source tables and does NOT read
 * these columns. Delete this class and the project loses all of its history: a director
 * could only ever see today's figures, and correcting one old timesheet would silently
 * rewrite what last March looked like.
 *
 * ONE FIGURE IS NOT COMPUTED AT ALL
 * ev_pct (Earned Value, the share of the work really earned) is a human judgement typed in
 * by the project manager (CdP = chef de projet) at the monthly review. No other table holds
 * it, so KpiService.computeLive reads it back from the most recent snapshot. That is why
 * this class is also the storage place for the EV estimate, the estimated end date and the
 * review notes: those three are inputs, not results.
 *
 * NOT STORED HERE
 * KpiResponse.margeVenduePct (the sold-margin baseline) has no column in this table;
 * SnapshotKpiMapper reads it from the parent project row at mapping time.
 *
 * The column layout comes from Flyway V8 (the base block) and V22 (the EVM block, spec
 * F-AFF-13 section 5). JPA never creates or alters the table: application.yml sets ddl-auto
 * to "validate" (ADR-019), so every precision and length written below is checked against
 * the real column at startup and a mismatch stops the application immediately.
 *
 * WHY THE CLASS HAS THIS SHAPE
 * It carries data only, with no arithmetic. All the formulas live in KpiService, so one
 * single implementation serves both the live screen and the saved snapshot. If the formulas
 * lived here they would only ever run when a row is created, and the live screen would need
 * a second copy of them that could slowly drift apart.
 *
 * extends BaseEntity adds the columns shared by every table in PMS: id, created_at,
 * updated_at, created_by, updated_by and the "deleted" flag. Why: PMS never really erases a
 * row, it sets deleted = true (soft delete), and every repository query filters on
 * deleted = false. Reusing BaseEntity also makes the audit columns fill themselves. Without
 * it this class would need its own id and its own audit columns, and a snapshot deleted by
 * mistake could never be recovered.
 *
 * ANNOTATIONS ON THE CLASS BELOW
 * The @Entity annotation tells Hibernate this class is a database table, not a plain Java object.
 * Why: Spring Data needs it to build SnapshotKpiRepository. Without it the application
 * refuses to start with "Not a managed type: com.pms.kpi.entity.SnapshotKpi", so no
 * snapshot could ever be saved or read.
 *
 * The @Table(name = "snapshot_kpis") annotation pins the exact table name.
 * Why: the name Hibernate would guess from the class name is "snapshot_kpi" (singular), but
 * Flyway V8 created "snapshot_kpis" (plural). Without this line the application would start
 * and then fail on the first query with: relation "snapshot_kpi" does not exist.
 *
 * The @Getter and @Setter annotations ask Lombok to write the get.../set... methods at compile time.
 * Why: Hibernate fills the row through those methods and MapStruct reads them to build
 * KpiResponse. Writing 19 pairs by hand would add hundreds of lines carrying no information.
 *
 * The @NoArgsConstructor annotation gives an empty constructor.
 * Why: when it loads a row, Hibernate first does "new SnapshotKpi()" and only then fills the
 * fields. Without it, reading the snapshot list fails with InstantiationException.
 *
 * The @AllArgsConstructor and @Builder annotations together give SnapshotKpi.builder().project(p)....build().
 * Why: this class has 19 fields and almost all of them are BigDecimal. A 19-argument
 * constructor would still compile if "eac" and "marge" were swapped, and the wrong amounts
 * would be saved with no error at all; the builder names every value at the call site, which
 * is how KpiService.createSnapshot writes it.
 * Note: this is @Builder, not @SuperBuilder, so the builder only knows the fields declared in
 * this class. id, created_at, updated_at and deleted cannot be set through it - they come
 * from BaseEntity and from Spring auditing.
 */
@Entity
@Table(name = "snapshot_kpis")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SnapshotKpi extends BaseEntity {

    /**
     * The project this snapshot belongs to. Gives access to project.getCode() and
     * project.getBudgetTnd(), which KpiService needs while computing.
     * WHY A RELATION AND NOT A PLAIN projectId NUMBER: SnapshotKpiMapper builds KpiResponse
     * with source = "project.code", and KpiService reads the budget straight from this
     * object, so a bare number would force an extra lookup at every single use.
     *
     * fetch = FetchType.LAZY means the project row is NOT read until someone actually calls
     * getProject(). Why: the snapshot list of a long project can return dozens of rows; with
     * the default EAGER each row would drag in a full project row. Concrete example of what
     * goes wrong without it: opening the snapshots tab of one project fires one query for
     * the snapshots plus one extra query per snapshot for the very same project - the
     * classic "N+1 queries" slowdown. This is also why
     * SnapshotKpiRepository.findActiveByProjectId writes "JOIN FETCH k.project": it loads
     * the project inside that one query, which matters because application.yml sets
     * open-in-view to false, so a lazy field touched after the transaction has ended throws
     * LazyInitializationException instead of loading quietly.
     *
     * The @JoinColumn(name = "project_id", nullable = false) annotation says the link is stored in the
     * project_id column and that the column can never be empty. Why: a snapshot with no
     * project is meaningless - nobody could tell whose figures those are. The database
     * enforces the same rule through the foreign key fk_kpi_project created in V8.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /**
     * The day the photo was taken. KpiService.createSnapshot always sets it to
     * LocalDate.now(), and KpiService.latestSnapshotEv() sorts on it to find the most recent
     * EV estimate.
     * WHY A DATE AND NOT A TIMESTAMP: a review is a working day, not an instant. A date also
     * turns "one snapshot per project per day" into a rule the database itself can enforce.
     *
     * nullable = false: a snapshot without a date could never be placed in the history, and
     * latestSnapshotEv() would crash while comparing it with the others.
     *
     * Matching database rule from V8: the unique index uk_kpi_project_date on
     * (project_id, snapshot_date) WHERE deleted = FALSE allows only one live snapshot per
     * project per day. The "WHERE deleted = FALSE" part matters: without it, soft-deleting a
     * wrong snapshot would still block the project manager from creating a corrected one the
     * same day. KpiService checks the same rule first, through
     * existsByProjectIdAndSnapshotDateAndDeletedFalse, so the user reads a clear message
     * instead of a raw database error.
     */
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /**
     * Planned cost of the project in TND: every line of the workload plan (plan_charges)
     * turned into money with the daily rate of the person, all added up.
     * WHY BigDecimal AND NOT double: double stores 0.1 as an approximation, so adding a few
     * hundred amounts ends at something like 124999.99999999997 and the total shown to the
     * director no longer matches the sum of the lines. BigDecimal counts in exact decimal
     * digits, which is what money needs.
     *
     * precision = 15, scale = 2 mirrors NUMERIC(15,2) in Flyway V8: at most 15 digits in
     * total, 2 of them after the decimal point, so up to 13 digits of dinars plus centimes.
     * Why repeat it here: ddl-auto is "validate", so these two numbers are the contract
     * between the Java class and the real column. Without them Hibernate would use its own
     * default size for a BigDecimal, which does not match NUMERIC(15,2), and the startup
     * check would refuse to start.
     *
     * No nullable = false: a project with no workload plan yet has no planned cost, and an
     * empty cell is honest where a 0 would be read as "nothing planned".
     */
    @Column(name = "budget_planifie", precision = 15, scale = 2)
    private BigDecimal budgetPlanifie;

    /**
     * Cost already really consumed in TND: only the validated timesheets (charges_reelles),
     * each turned into money with the daily rate that applied in its own year.
     * WHY ONLY VALIDATED LINES: a timesheet still waiting for approval may be refused or
     * corrected. Counting it would show a project as over budget on a cost that never happened.
     */
    @Column(name = "budget_consome", precision = 15, scale = 2)
    private BigDecimal budgetConsome;

    /**
     * EAC, Estimate At Completion: what the project is expected to cost in the end, in TND.
     * KpiService computes it as the cost already consumed plus the planned cost of the
     * periods that have no validated timesheet yet.
     * WHY NOT SIMPLY THE WHOLE PLAN ADDED TO THE REAL COST: the plan and the reality overlap.
     * Adding the full plan would count the finished months twice and roughly double the
     * forecast, so every project would look doomed.
     */
    @Column(name = "eac", precision = 15, scale = 2)
    private BigDecimal eac;

    /**
     * Forecast margin in TND: the project budget converted to TND, minus the EAC above.
     * A negative value is the alarm signal a director looks for - the project is heading for
     * a loss.
     * WHY IT CAN STAY EMPTY: if the project has no budget filled in yet, KpiService leaves
     * this null instead of writing 0, because "no budget entered" and "zero margin" are two
     * very different messages for the review.
     */
    @Column(name = "marge", precision = 15, scale = 2)
    private BigDecimal marge;

    /**
     * Burn rate: consumed cost divided by budget, stored as a fraction and not as a percent.
     * Example: 0.7532 means 75.32 % of the budget is already spent.
     *
     * precision = 7, scale = 4 mirrors NUMERIC(7,4) in V8: 4 digits after the decimal point.
     * Why 4 and not 2: the value is a fraction between 0 and 1, and it goes above 1 when the
     * project overruns. With only 2 decimals, 0.7532 would be stored as 0.75 and the screen
     * would show a flat 75 % for everything between 74.5 % and 75.5 %.
     */
    @Column(name = "taux_consommation", precision = 7, scale = 4)
    private BigDecimal tauxConsommation;

    // ── EVM indicators (spec F-AFF-13 section 5) - added by Flyway V22 ─────────────
    // EVM = Earned Value Management: the standard way to compare what was really produced
    // with what was really spent, instead of comparing spending against the calendar.

    /**
     * EV %, the share of the work considered really earned, from 0 to 100.
     * THIS ONE IS TYPED IN, NOT COMPUTED: the project manager (CdP) judges it at the monthly
     * review and sends it inside SnapshotRequest.
     * WHY IT CANNOT BE DERIVED: days spent do not prove work delivered. A team can burn 80 %
     * of the budget and have finished only 40 % of the scope; only a human can say which.
     * Because no other table holds it, KpiService.computeLive reads the value back from the
     * newest snapshot. This column is therefore what keeps the live screen able to show CA
     * production, FAE and the current margin between two reviews; with an empty EV,
     * KpiService adds a warning to KpiResponse and those three stay empty.
     *
     * precision = 5, scale = 2 mirrors NUMERIC(5,2) in V22: at most 999.99, which
     * comfortably holds 0.00 to 100.00. The 0-100 limit itself is checked in SnapshotRequest
     * with @DecimalMin / @DecimalMax, not here.
     */
    @Column(name = "ev_pct", precision = 5, scale = 2)
    private BigDecimal evPct;

    /**
     * Delivery %: deliverables actually delivered or accepted, divided by all planned
     * deliverables, times 100. KpiService counts the livrables whose status is LIVRE or VALIDE.
     * WHY IT SITS NEXT TO EV %: EV is an opinion, delivery % is a fact counted from the
     * deliverables table. Seeing both side by side is how the review spots a project manager
     * who is too optimistic.
     * Stays empty when the project has no deliverable at all - dividing by zero deliverables
     * has no meaning, and showing 0 % would look like a failing project.
     */
    @Column(name = "delivery_pct", precision = 5, scale = 2)
    private BigDecimal deliveryPct;

    /**
     * Days really consumed, in JH (jour-homme: one person working one day), summed from the
     * validated timesheets.
     * WHY DAYS AND NOT ONLY MONEY: money mixes two different problems, spending too many days
     * and using people who cost more per day. This column lets the review separate them.
     *
     * precision = 10, scale = 2 mirrors NUMERIC(10,2) in V22. Two decimals because a
     * timesheet can hold half or quarter days (0.50, 0.25); with no decimals, half a day of
     * work would be rounded away at every line.
     */
    @Column(name = "consomme_jh", precision = 10, scale = 2)
    private BigDecimal consommeJh;

    /**
     * RAF (reste a faire), the work left to do in JH: the planned days of every period that
     * has no validated timesheet yet.
     * WHY MEASURED THIS WAY: it reuses the workload plan the project manager already keeps up
     * to date, instead of asking for a second estimate that nobody would maintain and that
     * would soon contradict the plan.
     */
    @Column(name = "raf_jh", precision = 10, scale = 2)
    private BigDecimal rafJh;

    /**
     * Drift in JH: sold workload, minus days consumed, minus days remaining.
     * A negative value means the team will need more days than the client bought.
     * WHY IT IS MEASURED AGAINST THE SOLD WORKLOAD AND NOT THE PLAN: the plan can be edited
     * at any time, the sold workload is what the contract says. Comparing the plan with
     * itself would always give zero and would hide the overrun completely.
     * Stays empty when the project has no sold workload recorded, because there would be
     * nothing to compare against.
     */
    @Column(name = "derive_jh", precision = 10, scale = 2)
    private BigDecimal deriveJh;

    /**
     * CA production (production revenue) in TND: the project budget in TND multiplied by EV %.
     * It is the revenue the company has earned by doing the work, whether or not the client
     * has been invoiced yet.
     * WHY IT DEPENDS ON EV: revenue must follow work done, not the invoicing calendar.
     * Without it, a project invoiced in advance would look brilliantly profitable in its
     * first month and terrible at the end.
     */
    @Column(name = "ca_production", precision = 15, scale = 2)
    private BigDecimal caProduction;

    /**
     * Total already invoiced in TND: the billing milestones (jalons_facturation) whose status
     * is FACTURE (invoiced) or PAYE (paid), added up and converted to TND with the project
     * exchange rate.
     * WHY THE CONVERSION: a project can be sold in another currency, for example in FCFA.
     * Without multiplying by the rate, this amount would be compared against CA production in
     * TND on the line below, and the FAE would come out wildly wrong.
     */
    @Column(name = "total_facture", precision = 15, scale = 2)
    private BigDecimal totalFacture;

    /**
     * FAE (facture a etablir, invoice still to be issued) in TND: CA production minus the
     * total already invoiced. It is work delivered that the client has not been billed for yet.
     * WHY ACCOUNTING NEEDS IT: this is the amount to declare as earned but not yet invoiced
     * at the closing. Without it the company would under-declare its revenue for the period.
     * A negative FAE is useful too: it says the client was invoiced ahead of the work.
     */
    @Column(name = "fae", precision = 15, scale = 2)
    private BigDecimal fae;

    /**
     * Current margin in TND: CA production minus the cost really consumed.
     * WHY NOT BUDGET MINUS COST: that is the forecast margin, which is the "marge" column
     * above. This one compares what has been earned with what has been spent at the same
     * point in time, so it stays meaningful in the middle of a project. Without it, a project
     * that has spent 50 % of its budget would look healthy even if it had produced only 20 %
     * of the work.
     */
    @Column(name = "marge_actuelle", precision = 15, scale = 2)
    private BigDecimal margeActuelle;

    /**
     * Current margin as a fraction of CA production. Example: 0.4412 means 44.12 %.
     * WHY A PERCENT NEXT TO THE AMOUNT: only the percent can be compared with the sold margin
     * baseline (margeVenduePct in KpiResponse) and between projects of very different sizes.
     * Stays empty when CA production is zero or missing, because dividing by it would be
     * either impossible or meaningless.
     *
     * Same NUMERIC(7,4) reasoning as taux_consommation above: 4 decimals keep the percent
     * readable to two decimal places once it is multiplied by 100 on screen.
     */
    @Column(name = "marge_actuelle_pct", precision = 7, scale = 4)
    private BigDecimal margeActuellePct;

    /**
     * The end date the project manager expects at the moment of the review, typed in through
     * SnapshotRequest.
     * WHY IT IS STORED HERE AND NOT ON THE PROJECT: keeping it on the project would overwrite
     * the previous answer every month. Kept inside each snapshot, the history shows a date
     * that keeps sliding, which is exactly the warning sign a steering committee looks for.
     * Like ev_pct, KpiService.computeLive reads the newest one back for the live screen.
     */
    @Column(name = "date_fin_estimee")
    private LocalDate dateFinEstimee;

    /**
     * Free text notes of the review: the notable events of the month, written by the project
     * manager.
     * WHY IT BELONGS IN THE SNAPSHOT: it is the sentence that explains the numbers of that
     * exact month ("the client blocked us for three weeks"). A year later the figures alone
     * would be impossible to interpret.
     *
     * length = 2000 mirrors VARCHAR(2000) in V22. Why a length at all: the default JPA
     * mapping is VARCHAR(255), so without this line the startup validation would fail against
     * the real column. Why 2000 and not unlimited text: it keeps the field a short summary,
     * and a whole pasted report inside a KPI row would slow down every snapshot list query.
     * SnapshotRequest enforces the same 2000 with @Size, so a note that is too long is
     * refused with a clear validation message instead of a database error.
     */
    @Column(name = "faits_marquants", length = 2000)
    private String faitsMarquants;
}

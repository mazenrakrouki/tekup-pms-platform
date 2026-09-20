package com.pms.project.entity;

import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * One row of the projects table: the identity card of a project (contract, dates, budget,
 * currency, workload, people in charge). Central row of the app — timesheets, invoicing
 * milestones, KPIs, teams and the internal quote all hang off it. Columns were added across
 * Flyway V5/V14/V16/V22 and are validated against the schema at startup (ddl-auto=validate,
 * ADR-019). Formulas needing other tables (real costs, progress, invoiced amounts) live in
 * KpiService/JalonService/DevisInterneService instead, so each is implemented once.
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
     * Short business key, e.g. "PRJ-2025-014" — what people use in meetings and invoices.
     * Uniqueness is enforced by the partial index uk_projects_code (WHERE deleted = FALSE, V18),
     * not by a column constraint here, so a code frees up again once its project is soft-deleted;
     * ProjectService also checks it first for a clean error message and upper-cases every code.
     */
    @Column(nullable = false, length = 20)
    private String code;

    /** The readable title of the project. Required, and long enough for a real contract title. */
    @Column(nullable = false, length = 255)
    private String name;

    /** Free description; columnDefinition = "TEXT" since a description copied from a tender can run several paragraphs. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Life cycle state; allowed transitions live in ProjectStatus itself, checked by
     * ProjectService.changeStatus before writing here. Stored as STRING (not ORDINAL) so
     * inserting a new status later can't reinterpret existing rows; defaults to DRAFT via
     * @Builder.Default.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.DRAFT;

    /** Contractual start date. LocalDate (no time/zone), since a contract starts on a day, not a UTC instant. Nullable: dates are often agreed after the sheet is opened. */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** Contractual end date. chk_project_dates (V17) allows end == start, matching getDurationDays()'s inclusive count below. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * Budget signed at the start, in the project currency; never modified afterwards — it's the
     * reference everything else is compared against. BigDecimal, not double, to avoid binary
     * rounding drift on a budget checked line by line against a signed contract.
     */
    @Column(name = "initial_budget", precision = 15, scale = 2)
    private BigDecimal initialBudget;

    /**
     * Budget as it stands today after amendments ("avenants"); written by AvenantService, null
     * until one is approved. Kept separate from initialBudget so the two questions "what did we
     * sign" and "what are we working with now" stay both answerable — read via getEffectiveBudget()
     * below, never directly.
     */
    @Column(name = "revised_budget", precision = 15, scale = 2)
    private BigDecimal revisedBudget;

    // ── Identity card block ("Fiche d'identification", Excel model) — added by V14/V22 ──
    // ProjectService.applyFicheIdentification() writes this whole block in one go. All fields
    // below are nullable: the sheet is filled in progressively.

    /** Client's own contract reference; kept as text since it may contain letters/slashes and must match the paper archive. */
    @Column(name = "contract_id", length = 100)
    private String contractId;

    /** Name of the client who signed the contract. */
    @Column(length = 255)
    private String client;

    /** The organisation actually paying ("bailleur de fonds"), e.g. a development bank — often distinct from client on publicly funded work. */
    @Column(length = 255)
    private String funder;                 // Funder / donor

    /** Alone or in a consortium — see BusinessModel. Stored as STRING so a later enum addition can't reinterpret saved rows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "business_model", length = 20)
    private BusinessModel businessModel;   // SEUL | GROUPEMENT

    /** Fixed price or time and materials — see EngagementType. */
    @Enumerated(EnumType.STRING)
    @Column(name = "engagement_type", length = 20)
    private EngagementType engagementType; // FORFAIT | REGIE

    /**
     * Contract currency (TND, EUR, FCFA...). Every amount on this project is expressed in THIS
     * currency unless the field name says tnd. Defaults to "TND"; ProjectService upper-cases
     * whatever the user types.
     */
    @Column(length = 10)
    @Builder.Default
    private String currency = "TND";       // Project currency (FCFA, TND, EUR...)

    /**
     * How many TND one unit of the project currency is worth; amount in TND = amount x this
     * rate. Stored on the project (not fetched live) so the company's own reporting stays
     * consistent over the life of the contract. scale = 6 because a 2-decimal rate would round
     * a currency like FCFA (~0.005 TND) to zero precision. Defaults to 1 so an all-TND project
     * needs no special case.
     */
    @Column(name = "exchange_rate_to_tnd", precision = 15, scale = 6)
    @Builder.Default
    private BigDecimal exchangeRateToTnd = BigDecimal.ONE;

    /** Share of the budget for licences/subcontracting — money passed straight through, tracked apart from in-house work. Blanked by withoutFinancials() (BR-050). */
    @Column(name = "license_subcontract_budget", precision = 15, scale = 2)
    private BigDecimal licenseSubcontractBudget;

    /** Total workload sold to the client, in man-days (JH); the volume side of the contract, compared by KpiService against days actually booked. Not financial, so it stays visible under BR-050. */
    @Column(name = "sold_workload_days", precision = 10, scale = 2)
    private BigDecimal soldWorkloadDays;   // Sold workload (man-days)

    /** Man-days reserved for the warranty period after delivery — kept apart from soldWorkloadDays so the project doesn't look like it has build budget that's really reserved for fixes. */
    @Column(name = "warranty_workload_days", precision = 10, scale = 2)
    private BigDecimal warrantyWorkloadDays; // Warranty workload (man-days)

    /** Money set aside for contractual late-delivery penalties (PPP on the Excel sheet). Financial, so withoutFinancials() blanks it (BR-050). */
    @Column(name = "penalty_provision", precision = 15, scale = 2)
    private BigDecimal penaltyProvision;   // PPP

    /**
     * Net margin the project was SOLD at, as a coefficient (0.4412 = 44.12%) — a commercial
     * baseline typed by hand. KpiService prefers DevisInterneService.computeMargeVenduePct()
     * and falls back to this value only when the project has no DI line at all, so the computed
     * margin always wins when one exists.
     */
    @Column(name = "marge_nette_vendue", precision = 7, scale = 4)
    private BigDecimal margeNetteVendue;   // Sold baseline (e.g. 0.4412 = 44.12%); the computed DI margin wins when one exists

    /**
     * Archived flag: a finished project moved out of the working lists but still fully
     * readable. Distinct from "deleted" (hides everywhere) and status CANCELLED (says the work
     * stopped); this is only about tidying the screen, and archive()/unarchive() toggle it
     * freely without touching the life cycle. ProjectRepository's findAllActive/findAllArchived
     * split on this flag.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean archived = false;      // archived project (finished, out of the active lists)

    /**
     * The director responsible for the project, above the chef de projet. Lazy @ManyToOne;
     * every read in ProjectRepository uses LEFT JOIN FETCH on both director and chefProjet to
     * avoid N+1 queries when listing many projects (open-in-view is false, so a lazy field
     * touched after the transaction closes would otherwise throw).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "director_id")
    private User director;

    /**
     * The chef de projet, who runs the project day to day. Part of the security model, not
     * just the project sheet: ProjectRepository.findAccessibleProjectIdsByEmail matches on
     * chefProjet.email, so being named here is one of the two ways a user may open the project
     * at all (ADR-021). Reassigning it is guarded by its own permission, ASSIGN_CHEF_PROJET,
     * rather than by plain EDIT_PROJECT, since it changes who can see the project.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chef_projet_id")
    private User chefProjet;

    // ── Derived values (computed on every read, never stored) ────────────────────
    // Each is built from fields of this same row, so nothing here can go stale the way a saved
    // copy would after an amendment or a rate correction. MapStruct matches these getters by
    // name onto ProjectResponse's durationDays/budgetTnd/pprTnd automatically.

    /**
     * The budget in force today: revisedBudget when there is one, otherwise initialBudget.
     * All callers (AvenantService, JalonService) must go through this rather than reading the
     * two fields directly, so an amendment is never missed downstream.
     */
    public BigDecimal getEffectiveBudget() {
        return revisedBudget != null ? revisedBudget : initialBudget;
    }

    /**
     * Contract length in days, both ends counted (Excel convention): 1st to 3rd = 3, not 2.
     * Null, not 0, when a date is missing, since an unknown length isn't the same as a zero one.
     */
    public Long getDurationDays() {
        if (startDate == null || endDate == null) return null;
        return ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /**
     * Effective budget converted to TND, needed because KpiService compares TND costs against
     * a budget held in the client's currency. Null when there's no budget; falls back to a
     * rate of 1 if the field itself is null (e.g. an in-memory object not yet loaded).
     */
    public BigDecimal getBudgetTnd() {
        BigDecimal eff = getEffectiveBudget();
        if (eff == null) return null;
        BigDecimal rate = exchangeRateToTnd != null ? exchangeRateToTnd : BigDecimal.ONE;
        return eff.multiply(rate);
    }

    /**
     * "Provision Pour Risques": 5% of the budget in TND, per the Excel identity sheet. Null
     * when there's no budget. The rate is built from the String "0.05", not the double 0.05,
     * to avoid binary rounding drift on large projects.
     */
    public BigDecimal getPprTnd() {
        BigDecimal tnd = getBudgetTnd();
        return tnd != null ? tnd.multiply(new BigDecimal("0.05")) : null;
    }
}

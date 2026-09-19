package com.pms.project.dto;

import com.pms.project.entity.BusinessModel;
import com.pms.project.entity.EngagementType;
import com.pms.project.entity.ProjectStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * FILE: ProjectRequest.java
 *
 * WHAT THIS FILE IS
 * The shape of the JSON body sent when a project is created or updated. It is a DTO (Data
 * Transfer Object): an object used only to carry data over the network. It also carries the
 * input rules, written as Bean Validation annotations, so a bad value never reaches the service.
 * Its fields follow the company Excel sheet "Fiche identification" (workbook F-AFF-13), which is
 * the paper form this screen replaces.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular project form (JSON body)
 *     -> ProjectController.create (POST /api/projects) or update (PUT /api/projects/{id}),
 *        where the parameter is marked @Valid
 *     -> a broken rule below gives 400 Bad Request and the service is never entered
 *     -> ProjectService.create / update, which are guarded by the CREATE_PROJECT and
 *        EDIT_PROJECT permissions through @PreAuthorize (the check sits on the service, not on
 *        the controller), copy the simple fields and then call applyFicheIdentification(...)
 *        for the identification block
 *     -> the saved Project is mapped back through ProjectMapper into a ProjectResponse.
 *
 * WHY IT EXISTS
 * The controller must never accept the Project entity itself. The entity also has revisedBudget,
 * archived, deleted, createdAt and the two User links. If the client could set them in the body
 * it could revise its own budget with no avenant (contract amendment), un-archive a closed
 * project, or resurrect a deleted one, all without passing through the services that guard those
 * actions. This record exposes only what a user is allowed to type.
 *
 * WHAT IT DELIBERATELY DOES NOT CONTAIN
 * - revisedBudget: only an avenant may change it (AvenantService), never the project form.
 * - archived / deleted: they have their own endpoints and their own rules (a project must be
 *   COMPLETED before it can be archived).
 * - the computed figures (duration, budget in TND, PPR): they are derived from the fields below,
 *   so accepting them would let the client contradict its own data.
 *
 * NOTE ON THE TWO USER IDS
 * directorId and chefProjetId are present here, but sending them is not enough. ProjectService
 * applies them only if the caller holds the right permission (CREATE_PROJECT for the director,
 * ASSIGN_CHEF_PROJET for the project manager). A project manager who may edit his project
 * therefore cannot use this body to hand the project to somebody else.
 */

/**
 * Everything a user may type about a project, in one immutable object.
 *
 * Why one record for both create and update: both screens send the same form, so a second record
 * would only be a place to forget a rule. The two operations differ by URL and by permission,
 * not by payload.
 *
 * Reading the annotations below: Bean Validation ignores null, so every rule except @NotBlank
 * and @NotNull means "if a value is present, it must look like this". That is wanted here: a
 * project is created early, with a name and a code, and the financial block is filled in later.
 */
public record ProjectRequest(
        // @NotBlank refuses null, "" and a text made only of spaces (@NotNull alone would accept
        // "   "). @Size(max = 20) matches the code column, VARCHAR(20).
        // Why the code must always be there: the service upper-cases it and checks that no other
        // live project already uses it, so it is the business key people quote in meetings.
        // Without @NotBlank a project could be saved with a blank code and break that lookup;
        // without @Size the insert would fail inside PostgreSQL as a 500 instead of a clean 400.
        @NotBlank @Size(max = 20) String code,
        // Same reasoning for the display name, capped at the 255 characters of its column.
        @NotBlank @Size(max = 255) String name,
        // Free text, stored as TEXT in the database, so no length rule is needed.
        String description,
        // Lifecycle status. It is an enum, so only DRAFT, ACTIVE, ON_HOLD, COMPLETED or
        // CANCELLED can arrive; anything else is rejected by the JSON reader before validation.
        // It may be null: the service then keeps DRAFT for a new project and leaves the existing
        // status untouched on an update (assigning a null blindly would wipe a NOT NULL column
        // and the save would fail).
        // Be precise about this field if you are questioned on it. PATCH /api/projects/{id}/status
        // is the only path that checks the life cycle, by calling ProjectStatus.canTransitionTo(),
        // and it is the one the projects screen uses for a status change. ProjectService.update()
        // writes a non-null status from this body straight onto the project without asking that
        // question, so the life-cycle rule lives on the dedicated endpoint, not on this field.
        ProjectStatus status,
        // Contract start and end. The duration shown to the user is derived from these two
        // dates: Project.getDurationDays() counts the days between them with BOTH ends
        // included, so the 1st to the 31st is 31 days, exactly as on the Excel sheet.
        // Note that no Bean Validation rule here says the end must follow the start. The rule
        // exists, but one level down: the table carries the constraint chk_project_dates,
        // relaxed by Flyway V17 to "end_date IS NULL OR end_date >= start_date" so that a
        // one-day project is legal. Consequence to know if you are asked: reversed dates are
        // refused by PostgreSQL rather than by a clean 400 naming the field.
        LocalDate startDate,
        LocalDate endDate,
        // @PositiveOrZero accepts 0 and positive amounts and refuses a negative one.
        // Why: the budget is the base of the budget in dinars, of the 5 % risk provision and of
        // every planned billing milestone. A negative budget would produce a negative provision
        // and negative milestone amounts, and the whole KPI chain would inherit the sign error.
        // Zero is allowed because an internal or pilot project can genuinely start with none.
        @PositiveOrZero BigDecimal initialBudget,
        // Ids of the director and of the project manager, or null to leave them as they are.
        // They are ids and not embedded objects: the service loads the real users and refuses an
        // unknown or deleted one, so the body cannot invent a person.
        Long directorId,
        Long chefProjetId,

        // -- Identification sheet (Excel model) -------------------
        // Reference of the signed contract, capped at the 100 characters of its column.
        @Size(max = 100) String contractId,
        // The client who signs and the funder who pays. They are often different: a ministry
        // signs while a development bank funds. Both are capped at their 255-character columns.
        @Size(max = 255) String client,
        @Size(max = 255) String funder,
        // SEUL (alone) or GROUPEMENT (consortium with partners). An enum, so the value can only
        // be one of the two the business defined.
        BusinessModel businessModel,
        // FORFAIT (fixed price: the company carries the risk of the extra days) or REGIE (time
        // and materials: the client pays the days actually spent). This drives how a cost
        // overrun is read, which is why it is a closed list and not free text.
        EngagementType engagementType,
        // Currency of the contract (TND, EUR, FCFA...), capped at the 10 characters of its
        // column. The service upper-cases it and keeps the current value when it arrives empty.
        @Size(max = 10) String currency,
        // How many dinars one unit of that currency is worth.
        // @PositiveOrZero blocks a negative rate, which would flip the sign of every converted
        // amount of the project. The column keeps 6 decimals because small-unit currencies need
        // them: 1 FCFA is about 0.005850 TND, and rounding that to 0.01 would overstate a
        // one-million-FCFA contract by roughly 70 %.
        // Like the currency above, a null here does NOT blank the stored value: the service
        // only writes the rate when the form really sent one. Why that guard matters: the
        // entity defaults the rate to 1, so a null written through would silently make a budget
        // in FCFA be read as if it were already in dinars, and every converted figure of the
        // project - budget in TND, risk provision, and the whole Devis Interne - would be
        // roughly 170 times too big.
        @PositiveOrZero BigDecimal exchangeRateToTnd,
        // Part of the budget that goes to bought licences and to subcontractors. It is money the
        // company does not keep, so it is tracked apart from the workload.
        @PositiveOrZero BigDecimal licenseSubcontractBudget,
        // Workload sold to the client, and workload kept in reserve for the warranty period,
        // both in JH (one person working one day).
        // These two are quantities, not money. That distinction matters: they stay visible to
        // users who are walled off from financial data (BR-050), while the amounts around them
        // are stripped out.
        @PositiveOrZero BigDecimal soldWorkloadDays,
        @PositiveOrZero BigDecimal warrantyWorkloadDays,
        // Money set aside for late-delivery penalties written in the contract. Negative makes no
        // sense: a provision is an amount put away, never taken back.
        @PositiveOrZero BigDecimal penaltyProvision,
        // Net margin sold, as a fraction: 0.4412 means 44.12 %.
        // @DecimalMin("-1.0") and @DecimalMax("1.0") keep it inside the range -1 to 1. The
        // maximum stops the classic mistake of typing 44 for "44 %", which would claim a margin
        // 100 times the contract value and poison every dashboard reading it. The minimum still
        // allows a negative margin, because a project sold at a loss is a real case the company
        // must be able to record; -1 means the whole contract value is lost.
        // This figure is the commercial baseline typed by hand. When the project has DI lines,
        // the margin computed from the Devis Interne takes priority over it.
        @DecimalMin("-1.0") @DecimalMax("1.0") BigDecimal margeNetteVendue
) {}

package com.pms.project.dto;

import com.pms.project.entity.BusinessModel;
import com.pms.project.entity.EngagementType;
import com.pms.project.entity.ProjectStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

// Shape of one project as sent to the browser: stored columns, director/chefProjet flattened to
// id+name (avoids serializing lazy User entities, password hash included), and three derived
// figures (duration, budget in TND, risk provision). withoutFinancials() closes the BR-050 hole
// where GET /api/projects/{id} would otherwise leak amounts to a caller with VIEW_PROJECT but not
// VIEW_KPI; ProjectService decides whether to call it based on the VIEW_KPI permission, never a
// role name (ADR-001).

/**
 * One project, as read by the client. A record, so the redaction below can't be undone further
 * down the chain — withoutFinancials() builds a new object rather than mutating this one.
 */
public record ProjectResponse(
        Long id,
        // Business key, always upper-case, e.g. "PRJ-2024-07".
        String code,
        String name,
        String description,
        // Lifecycle state; allowed transitions live in ProjectStatus itself.
        ProjectStatus status,
        LocalDate startDate,
        LocalDate endDate,
        // initialBudget is what was signed; revisedBudget is what avenants made of it (null
        // until one exists); effectiveBudget (from Project.getEffectiveBudget()) is the one to
        // use in any calculation. All three travel together so an amendment stays visible.
        BigDecimal initialBudget,
        BigDecimal revisedBudget,
        BigDecimal effectiveBudget,
        // Director and chef de projet, flattened to id + display name; both null until assigned.
        Long directorId,
        String directorName,
        Long chefProjetId,
        String chefProjetName,

        // -- Identification sheet (Excel model) -------------------
        String contractId,
        // Client (signs) and funder (pays) — often different bodies.
        String client,
        String funder,
        // SEUL (alone) or GROUPEMENT (consortium).
        BusinessModel businessModel,
        // FORFAIT (fixed price) or REGIE (time and materials).
        EngagementType engagementType,
        // Currency and its rate to TND; sent even without financial rights since they're labels,
        // not amounts, and every converted figure below is meaningless without them.
        String currency,
        BigDecimal exchangeRateToTnd,
        BigDecimal licenseSubcontractBudget,
        // Sold and warranty workload, in JH — quantities, not money, so withoutFinancials() keeps
        // them.
        BigDecimal soldWorkloadDays,
        BigDecimal warrantyWorkloadDays,
        BigDecimal penaltyProvision,
        // Net margin sold, as a fraction (0.4412 = 44.12%); the DI-computed margin takes
        // priority when DI lines exist.
        BigDecimal margeNetteVendue,

        // True once the project is closed and moved out of the active lists; a flag, not a
        // status, so COMPLETED and archived can hold at the same time. Primitive boolean (not
        // Boolean) so the JSON always carries true/false, never a missing value.
        boolean archived,

        // -- Audit -----------------------------------------------
        LocalDateTime createdAt,

        // -- Computed fields (not stored) ------------------------
        // Contract length in days, both ends included; null while a date is missing (not 0, to
        // avoid implying "no length" for "not agreed yet").
        Long durationDays,
        // Effective budget converted to TND; null when there's no budget at all.
        BigDecimal budgetTnd,
        // PPR, "Provision Pour Risques" = budgetTnd x 5%, per the identification sheet.
        BigDecimal pprTnd
) {

    /**
     * Copy of this project with every financial value nulled out (BR-050): amounts and margin
     * gone, workload in JH (soldWorkloadDays, warrantyWorkloadDays) kept since it isn't money.
     * Returns null rather than 0 so the screen shows nothing instead of a misleading "0 TND".
     */
    public ProjectResponse withoutFinancials() {
        return new ProjectResponse(
                id, code, name, description, status, startDate, endDate,
                // the three budgets go away: this is the data BR-050 is about
                null, null, null,                       // initialBudget, revisedBudget, effectiveBudget
                directorId, directorName, chefProjetId, chefProjetName,
                // identity of the contract stays: it is who and what, never how much.
                // currency and exchangeRateToTnd stay too, because they are labels, not amounts
                contractId, client, funder, businessModel, engagementType, currency, exchangeRateToTnd,
                null,                                   // licenseSubcontractBudget
                // kept on purpose: days sold and days of warranty are workload, not money
                soldWorkloadDays, warrantyWorkloadDays,
                null,                                   // penaltyProvision
                null,                                   // margeNetteVendue
                // durationDays is kept: a calendar length tells nothing about money
                archived, createdAt, durationDays,
                // both derived from the budget, so they would leak it back: budgetTnd is the
                // budget itself converted, and pprTnd is 5 % of it, which anyone can multiply
                // by 20 to recover the budget
                null, null                              // budgetTnd, pprTnd
        );
    }
}

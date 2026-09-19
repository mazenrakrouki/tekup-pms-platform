package com.pms.billing.service;

import com.pms.billing.dto.FacturerRequest;
import com.pms.billing.dto.JalonRequest;
import com.pms.billing.dto.JalonResponse;
import com.pms.billing.entity.JalonFacturation;
import com.pms.billing.entity.JalonStatut;
import com.pms.billing.mapper.JalonMapper;
import com.pms.billing.repository.JalonFacturationRepository;
import com.pms.billing.repository.PaiementRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/*
 * WHAT THIS FILE IS
 * -----------------
 * Business service for "jalons de facturation" (billing milestones).
 * A jalon is one step of the payment plan of a contract: "30% of the budget when the design is
 * accepted", "40% at delivery", and so on. It carries a percentage, the money amount that the
 * percentage represents, a planned date, and a status.
 *
 * The status is a small lifecycle, held by the JalonStatut enum:
 *     PREVU (planned)  ->  FACTURE (invoice sent)  ->  PAYE (fully paid)
 * Only a PREVU jalon can still be edited or removed; once an invoice exists the accounting is
 * closed and the row is frozen.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   HTTP request -> BillingController (/api/projects/{projectId}/jalons...)
 *                -> ProjectScopeInterceptor (ADR-021: caller may act on THIS project)
 *                -> JalonService (this file: permission check + business rules)
 *                -> JalonFacturationRepository (reads/writes "jalons_facturation")
 *                -> PaiementRepository        (sums the payments of one jalon)
 *                -> JalonMapper               (entity -> JalonResponse DTO)
 *
 * Three methods are NOT reached from the controller. They are internal doors used by neighbouring
 * services, and they are documented one by one lower in the file:
 *   - recomputePrevuMontants(...) is called by AvenantService and ProjectService (marker H-4);
 *   - recalculerStatut(...) and loadJalon(...) are called by PaiementService.
 *
 * WHY IT EXISTS
 * -------------
 * It is the only place that knows three rules the rest of the app depends on:
 *   1. the sum of the percentages of one project can never go above 100%;
 *   2. the money amount of a jalon is budget x percentage, rounded to cents;
 *   3. a jalon that is already invoiced or paid can no longer be changed, deleted, or recomputed.
 * Delete this class and those three rules disappear: a project could be billed 130% of its
 * contract, and a milestone already invoiced to the client could silently change value.
 */
@Service
// Lombok generates the constructor over the four `final` fields below, and Spring injects them.
// Without it: hand-written boilerplate, and no easy way for JalonServiceTest to pass in mocks.
@RequiredArgsConstructor
public class JalonService {

    private final JalonFacturationRepository jalonRepository;
    private final PaiementRepository         paiementRepository;
    private final ProjectRepository          projectRepository;
    private final JalonMapper                jalonMapper;

    /**
     * Returns the payment plan of one project: every non-deleted jalon, ordered by planned date.
     *
     * Why written this way: loadProject() is called for its side effect only (the 404). It lets the
     * caller tell "this project has no payment plan yet" apart from "this project id is wrong".
     */
    // VIEW_BILLING is the read-only financial permission. The check is on the service, not on the
    // controller, so the rule holds for every caller. It tests a permission, never a role name,
    // because the role/permission matrix lives in the database and is editable at runtime.
    // Without it: a team member with no financial rights could read the whole payment plan, which
    // exposes the contract value of the project.
    @PreAuthorize("hasAuthority('VIEW_BILLING')")
    // readOnly = true: one transaction, nothing is meant to be written.
    // Why: entities stay attached while the mapper reads them, and Hibernate skips the change
    // tracking it would otherwise do on every loaded row.
    // Without it: an accidental setter in the read path could be flushed to the database.
    @Transactional(readOnly = true)
    public List<JalonResponse> findByProject(Long projectId) {
        loadProject(projectId);
        // The repository query JOIN FETCHes the project, so the mapper can fill projectCode without
        // one extra SELECT per jalon (the "N+1 queries" problem).
        return jalonMapper.toResponseList(jalonRepository.findActiveByProjectId(projectId));
    }

    /**
     * Adds one milestone to the payment plan and returns it with its generated id.
     *
     * Why written this way: the percentage is checked BEFORE anything is built or saved. Validating
     * first and writing after means a refused request leaves no half-created row behind.
     */
    // MANAGE_BILLING, not VIEW_BILLING: this writes the payment plan of a contract.
    // Per the authorization matrix (BR-038) billing is a project-manager capability; a director
    // holds VIEW_BILLING only.
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    // One unit of work: the percentage is read from the database and the new row is written inside
    // the same transaction.
    // Without it, the SELECT that sums the percentages and the INSERT would run in two separate
    // transactions, so another user could insert a jalon in between and the total could pass 100%.
    @Transactional
    public JalonResponse create(Long projectId, JalonRequest request) {
        Project project = loadProject(projectId);
        // oldPct is null here because nothing is being replaced: this is a brand new jalon, so its
        // percentage is pure addition to the current total.
        validatePourcentageSum(projectId, null, request.pourcentage());

        // The money amount is computed once and stored on the row (see computeMontant below and
        // marker H-4 for why it is stored rather than derived at read time).
        BigDecimal montant = computeMontant(project, request.pourcentage());

        // Builder (Lombok @Builder on the entity): fields are named, so adding a column later cannot
        // silently shift positional arguments.
        // `statut` is not set here on purpose: the entity defaults it to PREVU, which is the only
        // legal starting point of the lifecycle.
        JalonFacturation jalon = JalonFacturation.builder()
                .project(project)
                .label(request.label())
                .pourcentage(request.pourcentage())
                .montant(montant)
                .datePrevue(request.datePrevue())
                .build();

        // save() gives back the instance carrying the database-generated id; that is the one mapped,
        // so the client receives the id it needs for a later update or delete.
        return jalonMapper.toResponse(jalonRepository.save(jalon));
    }

    /**
     * Edits a milestone that has not been invoiced yet, and returns the updated DTO.
     *
     * Why written this way: it reloads the row from the database instead of trusting the incoming
     * JSON. The client sends a label, a percentage and a date - never a status and never an amount -
     * so the fields that decide money and lifecycle cannot be forged from outside.
     */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public JalonResponse update(Long projectId, Long id, JalonRequest request) {
        JalonFacturation jalon = loadJalon(id);
        // Guards against id mixing between two projects the caller can both see: see
        // checkBelongsToProject at the bottom of this file.
        checkBelongsToProject(jalon, projectId);

        // Business rule: once an invoice has been issued the row is frozen.
        // BusinessRuleException maps to HTTP 422 (the request is well formed but the domain refuses
        // it), which is different from a 404 or a 403.
        // Without this guard: a project manager could change a milestone from 30% to 10% after the
        // client was invoiced for 30%, and the payment plan would no longer match the invoice.
        if (jalon.getStatut() != JalonStatut.PREVU) {
            throw new BusinessRuleException("Impossible de modifier un jalon déjà facturé ou payé");
        }

        // Here oldPct is the percentage currently stored: the new value REPLACES it, it is not added
        // on top. Without passing the old value, editing a 30% jalon to 31% would be counted as
        // +31% and a plan already at 100% would be refused for no reason.
        validatePourcentageSum(projectId, jalon.getPourcentage(), request.pourcentage());

        Project project = jalon.getProject();
        jalon.setLabel(request.label());
        jalon.setPourcentage(request.pourcentage());
        // The amount always follows the percentage. Recomputing it here is what keeps the stored
        // montant and the stored pourcentage consistent with each other.
        jalon.setMontant(computeMontant(project, request.pourcentage()));
        jalon.setDatePrevue(request.datePrevue());

        return jalonMapper.toResponse(jalonRepository.save(jalon));
    }

    /**
     * Marks a milestone as invoiced and records the invoice date.
     *
     * Why a separate method instead of letting update() change the status: an invoice is an
     * accounting event, not a field edit. Giving it its own endpoint means the transition can be
     * guarded ("only from PREVU") and can never happen as a side effect of an ordinary save.
     */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public JalonResponse facturer(Long projectId, Long id, FacturerRequest request) {
        JalonFacturation jalon = loadJalon(id);
        checkBelongsToProject(jalon, projectId);

        // Only PREVU -> FACTURE is legal. This blocks invoicing the same milestone twice, and blocks
        // pushing a PAYE milestone backwards.
        // Without it: clicking the "Facturer" button twice would overwrite the real invoice date with
        // today's date, and the accounting trail would be lost.
        if (jalon.getStatut() != JalonStatut.PREVU) {
            throw new BusinessRuleException("Seul un jalon en statut PREVU peut être facturé");
        }

        jalon.setStatut(JalonStatut.FACTURE);
        // The invoice date comes from the request, not from the server clock: an invoice is often
        // recorded in the tool a few days after it was really issued.
        jalon.setDateFacture(request.dateFacture());
        return jalonMapper.toResponse(jalonRepository.save(jalon));
    }

    /**
     * Removes a milestone from the payment plan - only while it is still planned.
     *
     * Why written this way: it is a soft delete (a `deleted` flag), not a real DELETE. Billing rows
     * must stay auditable, and payments point at them.
     */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public void delete(Long projectId, Long id) {
        JalonFacturation jalon = loadJalon(id);
        checkBelongsToProject(jalon, projectId);

        // Same freeze rule as update(): an invoiced or paid milestone cannot disappear.
        // Without it: deleting a paid milestone would orphan its payment rows, and the money received
        // would no longer be attached to anything in the payment plan.
        if (jalon.getStatut() != JalonStatut.PREVU) {
            throw new BusinessRuleException("Impossible de supprimer un jalon déjà facturé ou payé");
        }

        // Soft delete: every "findActive..." query filters `deleted = false`, so the row vanishes from
        // the API and from the percentage sum, but stays in the table.
        jalon.setDeleted(true);
        jalonRepository.save(jalon);
    }

    // ── Called by AvenantService / ProjectService (H-4) ───────────

    /**
     * H-4: recomputes the montant of the PREVU jalons when the effective budget changes.
     * FACTURE and PAYE jalons stay frozen (the accounting is closed).
     *
     * Called by AvenantService.create(), AvenantService.delete() and ProjectService.update() - the
     * three places where the effective budget of a project can move.
     *
     * Why written this way (H-4 decision): the montant is STORED on the row, so it would keep an old
     * value forever after a budget change. Option (a) of the audit was chosen - recompute the planned
     * rows - rather than option (b), computing the amount at read time, because an invoiced amount
     * must stay exactly what was printed on the invoice.
     * Concrete failure without this method: budget 100 000, a 30% jalon stored at 30 000. An avenant
     * adds 50 000. The plan still shows 30 000 while 30% of the contract is now 45 000, and the sum
     * of the jalons no longer matches the contract.
     *
     * No @PreAuthorize and no @Transactional here on purpose: this is not an API entry point. Every
     * caller has already checked its own permission (MANAGE_BILLING for avenants, EDIT_PROJECT for
     * the project form) and has already opened a transaction that this code simply joins. The method
     * is public only because ProjectService lives in another package.
     */
    public void recomputePrevuMontants(Project project) {
        // Spring Data derives this query from the method name: project id + status + deleted = false.
        // Filtering on PREVU in the QUERY (and not with an `if` afterwards) is what guarantees that a
        // FACTURE or PAYE row is never even loaded, so it can never be touched by mistake.
        List<JalonFacturation> prevus = jalonRepository.findByProjectIdAndStatutAndDeletedFalse(
                project.getId(), JalonStatut.PREVU);
        // Two early exits. Nothing to recompute when there is no planned jalon; and when the project
        // has no budget at all, computeMontant would return null and we would erase amounts that were
        // computed earlier from a budget that has since been cleared.
        if (prevus.isEmpty() || project.getEffectiveBudget() == null) return;
        // forEach with a lambda: each planned jalon keeps its own percentage and only its amount is
        // rebuilt from the new budget. The percentages of the plan are not touched.
        prevus.forEach(j -> j.setMontant(computeMontant(project, j.getPourcentage())));
        // saveAll() sends the whole list in one batch instead of one save() per row.
        // The entities are already managed, so Hibernate would flush them anyway; saving explicitly
        // makes the intention visible and keeps the method correct if it is ever called outside a
        // persistence context.
        jalonRepository.saveAll(prevus);
    }

    // ── Called by PaiementService after every payment ─────────────

    /**
     * Recomputes the status of one jalon from the payments recorded against it.
     *
     * Called by PaiementService right after a payment is created or cancelled.
     *
     * Why written this way: the status is derived from the sum of the payments, never set by hand.
     * The obvious alternative - letting the user tick "paid" - would let the status and the money
     * actually received drift apart.
     *
     * Package-private (no `public`) on purpose: only the classes of com.pms.billing.service may call
     * it. It is an internal step of a payment, not an operation a client can trigger, so it needs no
     * permission check of its own - PaiementService already required MANAGE_BILLING.
     */
    void recalculerStatut(JalonFacturation jalon) {
        // A jalon with no amount (project without a budget) has no target to compare against, so
        // "fully paid" has no meaning. Returning early avoids a NullPointerException on compareTo.
        if (jalon.getMontant() == null) return;
        // The query uses COALESCE(SUM(...), 0), so `total` is 0 and never null when the jalon has no
        // payment left. Without that COALESCE this line would return null and the compareTo below
        // would throw.
        BigDecimal total = paiementRepository.sumMontantByJalonId(jalon.getId());
        // compareTo, not equals: on BigDecimal, equals also compares the number of decimals, so
        // 30000 and 30000.00 are NOT equal while compareTo says they are the same amount.
        // >= 0 means paid in full or slightly overpaid (a client rounding up, or a currency gain).
        if (total.compareTo(jalon.getMontant()) >= 0) {
            jalon.setStatut(JalonStatut.PAYE);
        // The downgrade branch: the jalon was marked PAYE but the payments no longer cover it,
        // which happens when a payment is cancelled (soft-deleted).
        // It goes back to FACTURE when an invoice date exists, otherwise back to PREVU.
        // Without this branch, cancelling a wrong payment would leave the milestone showing as paid
        // forever, and the "outstanding amount" report would be wrong.
        } else if (jalon.getStatut() == JalonStatut.PAYE) {
            jalon.setStatut(jalon.getDateFacture() != null ? JalonStatut.FACTURE : JalonStatut.PREVU);
        }
        jalonRepository.save(jalon);
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Refuses a payment plan whose percentages would add up to more than 100%.
     *
     * oldPct is the percentage being replaced (null when a jalon is being created); newPct is the
     * value the user is asking for.
     *
     * Why the two parameters instead of one: create and update need the same rule but a different
     * arithmetic. Passing the old value lets one single method serve both, so the rule is written
     * once and cannot be implemented differently in two places.
     */
    private void validatePourcentageSum(Long projectId, BigDecimal oldPct, BigDecimal newPct) {
        // Sum of the percentages already stored for this project, deleted rows excluded.
        // The query wraps it in COALESCE(..., 0) so a project with no jalon yet returns 0, not null.
        BigDecimal current = jalonRepository.sumPourcentageByProjectId(projectId);
        // "What the total would become": remove the old value (0 when creating), add the new one.
        BigDecimal effective = current.subtract(oldPct != null ? oldPct : BigDecimal.ZERO).add(newPct);
        // compareTo(...) > 0 means strictly greater than 100. Exactly 100% is legal and is in fact
        // the normal end state of a complete payment plan.
        // BigDecimal.valueOf(100) rather than `new BigDecimal(100)`: valueOf reuses cached instances
        // and keeps the scale predictable.
        // Without this check: a plan of 30 + 40 + 50 would be accepted and the client would be
        // invoiced 120% of the contract.
        if (effective.compareTo(BigDecimal.valueOf(100)) > 0) {
            // The message shows the CURRENT total, which is what the user needs to fix the input.
            // toPlainString() avoids scientific notation such as 1E+2 in the message.
            throw new BusinessRuleException(
                    "La somme des pourcentages dépasserait 100% (actuel : " + current.toPlainString() + "%)");
        }
    }

    /**
     * Turns a percentage into an amount of money: effective budget x percentage / 100.
     *
     * Returns null when the project has no budget yet, which is a legal state (a project can be
     * created before its contract value is known).
     *
     * Why getEffectiveBudget() and not getInitialBudget(): the effective budget is the revised budget
     * when avenants exist, otherwise the initial one. Billing must follow the contract as it stands
     * today, not as it was signed.
     */
    private BigDecimal computeMontant(Project project, BigDecimal pourcentage) {
        BigDecimal budget = project.getEffectiveBudget();
        // Returning null instead of zero is deliberate: zero would read as "this milestone is worth
        // nothing", while null reads as "not computable yet". recalculerStatut() relies on that
        // difference to avoid marking such a jalon as fully paid by a payment of 0.
        if (budget == null) return null;
        // divide(divisor, scale, roundingMode): the result is pinned to 2 decimals, which is the
        // cent, and matches the column definition (precision 15, scale 2).
        // HALF_UP is the usual commercial rounding: 0.005 goes up to 0.01.
        // Without the scale and the rounding mode, divide() keeps the full scale of the operands -
        // 100000 x 33.33 / 100 would be stored as 33330.0000 instead of 33330.00 - and the same call
        // shape throws ArithmeticException as soon as a division does not end.
        return budget.multiply(pourcentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Checks that the jalon really hangs under the project named in the URL.
     *
     * Why this exists although ADR-021 already runs: the ProjectScopeInterceptor proves the caller
     * may work on project {projectId}. It says nothing about the jalon id in the rest of the path.
     * A user who legitimately manages projects 5 and 9 could otherwise send
     * PUT /api/projects/5/jalons/42 where jalon 42 belongs to project 9, and edit the payment plan of
     * project 9 through the URL of project 5.
     *
     * It answers 404, not 403, on purpose: replying "forbidden" would confirm that the row exists
     * somewhere else, which already leaks information.
     */
    private void checkBelongsToProject(JalonFacturation jalon, Long projectId) {
        // .equals() and not == : both sides are Long objects, and == would compare references.
        // Java caches small Long values, so == would work for id 5 and silently fail for id 1000 -
        // a bug that only appears once the database grows.
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalon.getId());
        }
    }

    /**
     * Loads a jalon that is not soft-deleted, or throws a 404.
     *
     * Package-private (no `public`, no `private`) on purpose: PaiementService sits in the same
     * package and reuses it, so both services load a jalon through the same query and share the same
     * `deleted = false` filter and the same error message. Nothing outside com.pms.billing.service
     * can reach it.
     */
    JalonFacturation loadJalon(Long id) {
        // findActiveById JOIN FETCHes the project, so callers can read jalon.getProject().getId()
        // without an extra query and without a lazy-loading error outside the transaction.
        return jalonRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Jalon introuvable : " + id));
    }

    /**
     * Loads a project that is not soft-deleted, or throws a 404.
     * Written once so the `deleted = false` filter and the error wording cannot diverge between the
     * read and the create paths.
     */
    private Project loadProject(Long id) {
        // orElseThrow converts the empty Optional into an exception the global handler maps to HTTP
        // 404 with an RFC 7807 body. Without it the code would carry an Optional around and a missing
        // project would only surface later as a NullPointerException.
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}

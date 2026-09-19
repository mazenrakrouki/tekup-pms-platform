package com.pms.billing.service;

import com.pms.billing.dto.AvenantRequest;
import com.pms.billing.dto.AvenantResponse;
import com.pms.billing.entity.Avenant;
import com.pms.billing.mapper.AvenantMapper;
import com.pms.billing.repository.AvenantRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/*
 * WHAT THIS FILE IS
 * -----------------
 * Business service for "avenants". An avenant is a signed change to the contract of a project:
 * the client agrees to add money (or to remove money) after the project already started.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   HTTP request  ->  BillingController (/api/projects/{projectId}/avenants)
 *                 ->  ProjectScopeInterceptor (ADR-021: checks the caller may touch THIS project)
 *                 ->  AvenantService (this file: checks the permission, applies the business rules)
 *                 ->  AvenantRepository  (reads and writes the "avenants" table)
 *                 ->  ProjectRepository  (writes projects.revised_budget)
 *                 ->  JalonService        (recomputes the billing milestones, marker H-4)
 *                 ->  AvenantMapper       (turns the entity into the AvenantResponse DTO sent back)
 *
 * A DTO ("Data Transfer Object") is a small flat object used only to carry data in and out of the
 * API. We never send the JPA entity itself, so the database shape stays private.
 *
 * WHY IT EXISTS
 * -------------
 * This class is the SINGLE WRITER of projects.revised_budget (audit marker C-1).
 * Before C-1 was fixed, the project edit form also wrote that column, and saving the project name
 * could silently erase the whole avenant history from the budget. If you deleted this class:
 *   - there would be no legal trace of contract changes,
 *   - the revised budget would have no owner again, and two screens could fight over it,
 *   - and every amount derived from the budget (milestones, KPI margin) would drift.
 */
@Service
// @RequiredArgsConstructor is Lombok. It generates a constructor that takes every `final` field.
// Why: Spring then injects the four collaborators through that constructor, so the fields can stay
// final and the object is never half-built.
// Without it: you would have to hand-write the constructor, or use field injection, and a test
// could no longer create the service with fake (mock) collaborators.
@RequiredArgsConstructor
public class AvenantService {

    private final AvenantRepository  avenantRepository;
    private final ProjectRepository  projectRepository;
    private final AvenantMapper      avenantMapper;
    private final JalonService       jalonService;

    /**
     * Returns every avenant of one project, oldest signature date first, as response DTOs.
     *
     * Why written this way: it calls loadProject() first even though the result is not used.
     * That single read turns "project 999 does not exist" into a clean 404 instead of an empty
     * list, so the caller can tell "no avenant yet" apart from "wrong project id".
     */
    // @PreAuthorize runs BEFORE the method body and refuses the call if the logged-in user does not
    // hold the VIEW_BILLING permission. It sits on the SERVICE, not on the controller, so any future
    // caller (another service, a scheduled job) is guarded too.
    // Note it tests a PERMISSION, never a role name: the role/permission matrix lives in the database
    // and can change without touching this code.
    // Without it: a developer with only workload rights could read the financial history of a project
    // by calling GET /api/projects/7/avenants.
    @PreAuthorize("hasAuthority('VIEW_BILLING')")
    // @Transactional(readOnly = true) wraps the method in one database transaction marked read-only.
    // Why: the entities loaded here stay attached during the mapping, and Hibernate skips its
    // "dirty checking" bookkeeping because nothing is meant to be written.
    // Without it: a stray setter anywhere in the read path could be flushed to the database by
    // accident, and the lazy `project` association could fail once the session closed.
    @Transactional(readOnly = true)
    public List<AvenantResponse> findByProject(Long projectId) {
        // Fails fast with 404 if the project id is unknown or soft-deleted (see loadProject below).
        loadProject(projectId);
        // findActiveByProjectId() filters out soft-deleted rows and JOIN FETCHes the project, so the
        // mapper can read project.code without firing one extra query per row (the "N+1" problem).
        return avenantMapper.toResponseList(avenantRepository.findActiveByProjectId(projectId));
    }

    /**
     * Records a new avenant and moves the project budget by the same amount.
     *
     * Returns the saved avenant as a DTO (with its generated id).
     *
     * Why written this way: the revised budget is an ACCUMULATOR, not a value typed by a user.
     * Each avenant adds its own montant on top of the current effective budget. The obvious
     * alternative - letting the project form write the revised budget directly - is exactly what
     * audit issue C-1 forbids, because the form had no memory of past avenants.
     */
    // MANAGE_BILLING (not VIEW_BILLING) because this method changes money.
    // Per the authorization matrix (BR-038) billing is a project-manager capability; a director only
    // holds VIEW_BILLING.
    // Without it: anyone who can open the project screen could inflate the contract value.
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    // @Transactional makes the whole method one single unit of work in the database.
    // Why: we write to two tables here (projects, then avenants) and we also rewrite the milestone
    // amounts through JalonService.
    // Without it, a crash after projectRepository.save(project) would leave the budget raised by
    // 50 000 with no avenant row to justify it - money appearing from nowhere in the accounts.
    @Transactional
    public AvenantResponse create(Long projectId, AvenantRequest request) {
        Project project = loadProject(projectId);

        // Update of the revised budget.
        // getEffectiveBudget() answers "the budget in force today": revisedBudget when it exists,
        // otherwise initialBudget. Starting from it is what makes avenants stack up.
        BigDecimal currentBudget = project.getEffectiveBudget();
        // The null test covers a project created without any budget yet; we then start from zero.
        // request.montant() may be negative: an avenant can also reduce the contract.
        // BigDecimal (not double) is used everywhere for money because double cannot store 0.10
        // exactly; adding it ten times with double gives 0.9999999999999999, and an invoice total
        // would be one cent off.
        BigDecimal newRevisedBudget = (currentBudget != null ? currentBudget : BigDecimal.ZERO)
                .add(request.montant());
        project.setRevisedBudget(newRevisedBudget);
        projectRepository.save(project);
        // H-4: milestone amounts are stored, not derived, so they must be rebuilt now that the
        // effective budget moved. Only PREVU (planned) milestones are touched; invoiced and paid ones
        // stay frozen, because their invoice is already in the accounts.
        // Without this call, a milestone saved as "30% = 30 000" would stay at 30 000 after a
        // +50 000 avenant, while 30% of the new budget is 45 000.
        jalonService.recomputePrevuMontants(project); // H-4

        // Builder pattern (Lombok @Builder on the entity): fields are set by name, so adding a field
        // later cannot silently shift arguments the way a long constructor call would.
        // Note that `deleted` is not set here: BaseEntity defaults it to false, and createdAt /
        // createdBy are filled automatically by JPA auditing.
        Avenant avenant = Avenant.builder()
                .project(project)
                .numero(request.numero())
                .objet(request.objet())
                .montant(request.montant())
                .workloadDays(request.workloadDays())
                .dateAvenant(request.dateAvenant())
                .build();

        // save() returns the managed instance carrying the database-generated id; we map THAT one,
        // so the response contains the real id the client needs for a later delete.
        return avenantMapper.toResponse(avenantRepository.save(avenant));
    }

    /**
     * Cancels an avenant: it takes its amount back out of the budget and soft-deletes the row.
     *
     * Why written this way: the row is flagged deleted instead of being erased. Billing data has to
     * stay auditable, and other tables point at it. A real DELETE would break that trail.
     */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    // One transaction again: the budget correction and the soft-delete flag must both land, or
    // neither. A crash between them would leave the budget reduced while the avenant still shows as
    // active in the list.
    @Transactional
    public void delete(Long projectId, Long id) {
        // findActiveById() already skips rows whose `deleted` flag is true, so deleting twice gives a
        // clean 404 instead of subtracting the same amount from the budget a second time.
        Avenant avenant = avenantRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Avenant introuvable : " + id));
        // Cross-check that the avenant really belongs to the project named in the URL.
        // ADR-021 / ProjectScopeInterceptor already proved the caller may work on {projectId}; this
        // second check stops id mixing between two projects the caller can both see.
        // It answers 404 (not 403) on purpose: telling the caller "this exists but elsewhere" would
        // leak the existence of a row on another project.
        // Without it: DELETE /api/projects/5/avenants/42 would cancel avenant 42 of project 9 and
        // subtract its amount from the budget of project 5.
        if (!avenant.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Avenant introuvable : " + id);
        }

        // Reversing the effect on the revised budget.
        // getEffectiveBudget() is used as the safe starting point (C-1). Reading revisedBudget
        // directly would be wrong for the first avenant ever cancelled on a project where that
        // column is still null: the subtraction would start from nothing instead of from the
        // initial budget.
        Project project = avenant.getProject();
        // The null test is a last safety net for a project that never had any budget at all.
        BigDecimal current = project.getEffectiveBudget() != null ? project.getEffectiveBudget() : BigDecimal.ZERO;
        // subtract() returns a NEW BigDecimal: BigDecimal is immutable, so `current` is unchanged and
        // the result must be assigned. Writing `current.subtract(x);` alone would do nothing.
        project.setRevisedBudget(current.subtract(avenant.getMontant()));
        projectRepository.save(project);
        // H-4 again: the effective budget just moved back down, so planned milestone amounts must
        // follow. Invoiced and paid milestones stay frozen.
        jalonService.recomputePrevuMontants(project); // H-4

        // Soft delete: the row stays in the table, every "findActive..." query ignores it from now on.
        avenant.setDeleted(true);
        avenantRepository.save(avenant);
    }

    /**
     * Loads a project that is not soft-deleted, or throws a 404.
     *
     * Why written this way: findByProject() and create() both need the same "exists and is alive"
     * check. Having it once means one single wording of the error and no way to forget the
     * `deleted = false` filter in one place only. delete() does not call it: it reaches the project
     * through the avenant it has just loaded.
     */
    private Project loadProject(Long id) {
        // orElseThrow() turns the empty Optional into NotFoundException, which the global exception
        // handler maps to HTTP 404 with an RFC 7807 body.
        // Without it, the code would work on an Optional and a missing project would surface much
        // later as a NullPointerException with no useful message for the client.
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}

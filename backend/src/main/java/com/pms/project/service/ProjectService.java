package com.pms.project.service;

import com.pms.billing.service.JalonService;
import com.pms.project.dto.ProjectRequest;
import com.pms.project.dto.ProjectResponse;
import com.pms.project.entity.Project;
import com.pms.project.entity.ProjectStatus;
import com.pms.project.mapper.ProjectMapper;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/*
 * Business service of the Project module: create/read/update a project, move it through its
 * life cycle, archive/delete it. A project is the signed contract (code, client, budget,
 * currency, director, chef de projet, dates); almost every other module hangs off this row.
 *
 * Holds five rules the rest of the app depends on: project codes are unique (case-insensitive,
 * stored upper) among non-deleted projects; only a COMPLETED project can be archived; status
 * changes must follow ProjectStatus's life cycle; changing the initial budget rebuilds planned
 * billing amounts (H-4); a caller without VIEW_KPI never receives an amount or margin (BR-050).
 *
 * Two layers of authorization, both needed: permission (@PreAuthorize, "may they do this kind
 * of action?") and scope (scopeService, "on which projects?", ADR-021) - a chef de projet holds
 * EDIT_PROJECT for his own projects only, so permission alone would let him archive a colleague's
 * project by editing the URL id. Checks sit on the service, not the controller, so back-end
 * callers like KpiService are covered too. Never tests a role name (ADR-001), only capabilities.
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectMapper projectMapper;
    private final ProjectScopeService scopeService;
    // Billing module, used only in update() (H-4) to rebuild milestone amounts after a budget change.
    private final JalonService jalonService;

    /**
     * Every active (not deleted, not archived) project the caller may see, mapped and
     * financial-blanked. Scope filter runs in Java after the query, not in SQL: the active list
     * is small, so refiltering in memory keeps this to one query (unlike the paged variant below).
     */
    // VIEW_PROJECT checked on the service so the rule holds for non-HTTP callers too.
    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public List<ProjectResponse> findAll() {
        List<Project> projects = projectRepository.findAllActive();
        // Skipped entirely for the portfolio-wide capability.
        if (!scopeService.hasAllAccess()) {
            Set<Long> accessible = scopeService.accessibleProjectIds(currentEmail());
            projects = projects.stream().filter(p -> accessible.contains(p.getId())).toList();
        }
        return toResponseList(projects);
    }

    /**
     * Paged version for GET /api/projects?page=0&size=20. Filters in SQL, unlike findAll()
     * above: filtering in Java after paging would return a page shorter than its claimed size
     * and could skip rows on the next page, so the id set has to be inside the WHERE clause.
     */
    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public Page<ProjectResponse> findAll(Pageable pageable) {
        if (scopeService.hasAllAccess()) {
            return projectRepository.findAllActivePaged(pageable).map(this::toResponse);
        }
        Set<Long> accessible = scopeService.accessibleProjectIds(currentEmail());
        // "WHERE p.id IN ()" is invalid SQL in PostgreSQL, hence this short-circuit.
        if (accessible.isEmpty()) return Page.empty(pageable);
        return projectRepository.findAllActiveByIdIn(accessible, pageable).map(this::toResponse);
    }

    /**
     * Archived projects the caller may see. A separate method (not a boolean on findAll()) so
     * the "archived = true" condition lives in SQL rather than an "if" someone could forget;
     * also needed because GET /api/projects/archived carries no id for the interceptor to scope.
     *
     * Note: accessibleProjectIds excludes archived projects by construction, so a caller
     * without VIEW_ALL_PROJECTS always sees an empty archived tab, even for a project they
     * archived themselves - a side effect of that query, not an intentional rule.
     */
    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public List<ProjectResponse> findArchived() {
        List<Project> projects = projectRepository.findAllArchived();
        if (!scopeService.hasAllAccess()) {
            Set<Long> accessible = scopeService.accessibleProjectIds(currentEmail());
            projects = projects.stream().filter(p -> accessible.contains(p.getId())).toList();
        }
        return toResponseList(projects);
    }

    /**
     * One project by id, 404 if unknown or soft-deleted. Scope is re-checked here even though
     * ProjectScopeInterceptor already checked the URL, because a future back-end caller could
     * reach this method directly and bypass ADR-021 otherwise.
     */
    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public ProjectResponse findById(Long id) {
        // Loaded first, so an unknown id produces 404 rather than a confusing 403.
        Project project = loadProject(id);
        scopeService.assertCanAccess(id, currentEmail()); // scope ADR-021
        return toResponse(project);
    }

    /**
     * Moves a finished project out of the active lists (422 unless status is COMPLETED).
     * Archiving is a display flag, separate from status: an archived project stays COMPLETED
     * for every report/KPI, so unarchiving changes nothing else.
     */
    // EDIT_PROJECT, not DELETE_PROJECT: archiving hides a project, it destroys nothing.
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse archive(Long id) {
        // Scope checked before loading, so an out-of-perimeter caller gets 403 without
        // learning whether the id even exists.
        scopeService.assertCanAccess(id, currentEmail());
        Project project = loadProject(id);
        if (project.getStatus() != ProjectStatus.COMPLETED) {
            throw new BusinessRuleException("Seul un projet terminé peut être archivé");
        }
        project.setArchived(true);
        return toResponse(projectRepository.save(project));
    }

    /**
     * Brings an archived project back. No status rule, unlike archive() - a wrongly archived
     * project must be recoverable in one click.
     */
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse unarchive(Long id) {
        // Asymmetry worth knowing: accessibleProjectIds excludes archived projects, so without
        // VIEW_ALL_PROJECTS this line 403s on the very project the caller just archived. In
        // practice only that capability's holder reaches unarchive.
        scopeService.assertCanAccess(id, currentEmail());
        Project project = loadProject(id);
        project.setArchived(false);
        return toResponse(projectRepository.save(project));
    }

    /** Creates a project. 409 if the code is already taken. No scope check: the project doesn't exist yet. */
    // CREATE_PROJECT is portfolio-level; a chef de projet holds only EDIT_PROJECT and cannot open new contracts.
    @PreAuthorize("hasAuthority('CREATE_PROJECT')")
    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        // Stored upper case: the human key, so "prj-01" and "PRJ-01" must collide, not coexist.
        String normalizedCode = request.code().toUpperCase();
        // AndDeletedFalse: a code freed by a soft-deleted project can be reused.
        if (projectRepository.existsByCodeAndDeletedFalse(normalizedCode)) {
            throw new IllegalArgumentException("Code projet déjà utilisé : " + normalizedCode);
        }

        // Builder, not a 20+ arg constructor, to avoid silently swapping two BigDecimal args.
        Project project = Project.builder()
                .code(normalizedCode)
                .name(request.name())
                .description(request.description())
                // DRAFT is the entry point of ProjectStatus's life cycle; also the NOT NULL fallback.
                .status(request.status() != null ? request.status() : ProjectStatus.DRAFT)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .initialBudget(request.initialBudget())
                .director(resolveUser(request.directorId()))
                .build();

        // Naming the chef de projet needs ASSIGN_CHEF_PROJET, not just CREATE_PROJECT: it's a
        // separate management decision and, via ADR-021, grants a data scope. Silently ignored
        // (not refused) when absent, so the rest of the creation still succeeds.
        if (request.chefProjetId() != null && hasAuthority("ASSIGN_CHEF_PROJET")) {
            project.setChefProjet(resolveUser(request.chefProjetId()));
        }
        applyFicheIdentification(project, request);
        return toResponse(projectRepository.save(project));
    }

    /**
     * Updates a project: check the code, copy the plain fields, then re-check the two fields
     * that carry authority (director, chef de projet) - which is why this isn't a plain copy.
     * 404 for an unknown id, 409 if the new code belongs to another project.
     */
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse update(Long id, ProjectRequest request) {
        Project project = loadProject(id);
        String normalizedCode = request.code().toUpperCase();

        // First half of the condition lets a project keep its own code on re-save.
        if (!project.getCode().equals(normalizedCode) && projectRepository.existsByCodeAndDeletedFalse(normalizedCode)) {
            throw new IllegalArgumentException("Code projet déjà utilisé : " + normalizedCode);
        }

        // H-4: if no avenant exists yet (revisedBudget null), the initial budget drives the
        // effective budget, so a change here must rebuild the PREVU milestone amounts. Computed
        // before the setters below, while the old value is still available to compare.
        boolean budgetWillChange = project.getRevisedBudget() == null
                && !budgetEqual(request.initialBudget(), project.getInitialBudget());

        project.setCode(normalizedCode);
        project.setName(request.name());
        project.setDescription(request.description());
        // Null means "form didn't touch it" - status is NOT NULL, so don't overwrite blindly.
        if (request.status() != null) project.setStatus(request.status());
        project.setStartDate(request.startDate());
        project.setEndDate(request.endDate());
        project.setInitialBudget(request.initialBudget());
        // Reserved to CREATE_PROJECT: a chef de projet (EDIT_PROJECT only) must not be able to
        // remove the director who supervises them.
        if (request.directorId() != null && hasAuthority("CREATE_PROJECT")) {
            project.setDirector(resolveUser(request.directorId()));
        }
        // Reserved to ASSIGN_CHEF_PROJET, so this field can't bypass assignChefProjet() below.
        if (request.chefProjetId() != null && hasAuthority("ASSIGN_CHEF_PROJET")) {
            project.setChefProjet(resolveUser(request.chefProjetId()));
        }
        applyFicheIdentification(project, request);

        Project saved = projectRepository.save(project);
        if (budgetWillChange) {
            // H-4: same transaction, so budget and milestone amounts save together or not at all.
            jalonService.recomputePrevuMontants(saved);
        }
        return toResponse(saved);
    }

    /**
     * Copies the "Fiche d'identification" fields (F-AFF-13) shared by create() and update(),
     * so a field added later can't be handled on one path and dropped on the other.
     */
    private void applyFicheIdentification(Project project, ProjectRequest request) {
        project.setContractId(request.contractId());
        project.setClient(request.client());
        project.setFunder(request.funder());
        project.setBusinessModel(request.businessModel());
        project.setEngagementType(request.engagementType());
        // Only overwritten when sent: entity defaults to "TND", blanking would break conversions.
        if (request.currency() != null && !request.currency().isBlank()) {
            project.setCurrency(request.currency().toUpperCase());
        }
        // Same guard, matters more here: entity defaults to 1, so a stray null would make
        // getBudgetTnd()/DI treat a foreign-currency budget as already in dinars.
        if (request.exchangeRateToTnd() != null) {
            project.setExchangeRateToTnd(request.exchangeRateToTnd());
        }
        project.setLicenseSubcontractBudget(request.licenseSubcontractBudget());
        project.setSoldWorkloadDays(request.soldWorkloadDays());
        project.setWarrantyWorkloadDays(request.warrantyWorkloadDays());
        project.setPenaltyProvision(request.penaltyProvision());
        project.setMargeNetteVendue(request.margeNetteVendue());
    }

    /**
     * Names the chef de projet. A dedicated endpoint, not the ordinary update form, because
     * this field decides who gets the project's data scope via ADR-021.
     */
    @PreAuthorize("hasAuthority('ASSIGN_CHEF_PROJET')")
    @Transactional
    public ProjectResponse assignChefProjet(Long projectId, Long userId) {
        Project project = loadProject(projectId);
        User chef = userRepository.findById(userId)
                // findById would still return a soft-deleted account; must exclude it here.
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + userId));

        project.setChefProjet(chef);
        return toResponse(projectRepository.save(project));
    }

    /** Moves the project to another status. 422 if ProjectStatus's life cycle forbids the move. */
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse changeStatus(Long id, ProjectStatus newStatus) {
        Project project = loadProject(id);
        if (!project.getStatus().canTransitionTo(newStatus)) {
            throw new BusinessRuleException("Transition de statut interdite : "
                    + project.getStatus() + " → " + newStatus);
        }
        project.setStatus(newStatus);
        return toResponse(projectRepository.save(project));
    }

    /**
     * Soft-deletes a project (row stays, "deleted" flag raised, filtered from every read
     * query) - a real DELETE would either be blocked by foreign keys or, cascaded, destroy the
     * workload/billing/KPI/DI history pointing at this row.
     */
    // DELETE_PROJECT is its own capability so editing a project sheet doesn't imply deleting it.
    @PreAuthorize("hasAuthority('DELETE_PROJECT')")
    @Transactional
    public void delete(Long id) {
        Project project = loadProject(id);
        project.setDeleted(true);
        projectRepository.save(project);
    }

    /**
     * Maps a Project to its JSON response, applying the BR-050 financial wall (no amount or
     * margin without VIEW_KPI). Done here, not in the mapper, so every read path shares the
     * one door. withoutFinancials() returns a copy, leaving the transaction-attached entity untouched.
     */
    private ProjectResponse toResponse(Project project) {
        ProjectResponse response = projectMapper.toResponse(project);
        return hasAuthority("VIEW_KPI") ? response : response.withoutFinancials();
    }

    private List<ProjectResponse> toResponseList(List<Project> projects) {
        return projects.stream().map(this::toResponse).toList();
    }

    /** Loads a non-deleted project or throws NotFoundException (→ 404). */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /** Current caller's e-mail, or null when unauthenticated - accessibleProjectIds(null) safely answers "sees nothing". */
    private String currentEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    /**
     * Whether the current user holds a capability (ADR-001: never a role name). Used where
     * @PreAuthorize can't apply, because the capability only changes part of the behavior
     * (e.g. whether chef de projet is set, whether amounts are kept) rather than the whole request.
     */
    private boolean hasAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    /** Resolves a browser-sent user id to a User, or null if none was sent. 404 if unknown/deleted. */
    private User resolveUser(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + userId));
    }

    /**
     * Compares two amounts by value (nulls equal, scale ignored): BigDecimal.equals() treats
     * 100 and 100.00 as different, which would make update() think the budget changed - and
     * call recomputePrevuMontants() - on every edit, even one that only changed the project name.
     */
    private static boolean budgetEqual(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }
}

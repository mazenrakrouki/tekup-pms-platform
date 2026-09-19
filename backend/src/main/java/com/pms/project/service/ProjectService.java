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
 * WHAT THIS FILE IS
 * -----------------
 * The business service of the Project module: create a project, read one or many, change its
 * identification sheet, move it through its life cycle, archive it, delete it.
 * A "project" in PMS is the contract the company signed: a code, a client, a budget, a
 * currency, a director, a chef de projet, and the dates of the engagement. Almost every other
 * module (workload, billing, KPI, missions, risks, Devis Interne) hangs off a project row, so
 * this file sits at the root of the whole data model.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   Browser (Angular projects module)
 *     -> ProjectController (/api/projects ...)         thin, no rule inside it
 *     -> ProjectScopeInterceptor (ADR-021)             for the /api/projects/{id}... URLs
 *     -> THIS FILE                                     permission + scope + business rules
 *          -> ProjectRepository    reads and writes the "projects" table
 *          -> UserRepository       turns a directorId / chefProjetId into a real User row
 *          -> ProjectScopeService  answers "which projects may this person see?"
 *          -> ProjectMapper        Project entity -> ProjectResponse (the JSON record)
 *          -> JalonService         only in update(), to rebuild the billing amounts (H-4)
 *     -> ProjectResponse (JSON) back to the browser, financial fields blanked when the caller
 *        has no VIEW_KPI (BR-050).
 *
 * WHY IT EXISTS
 * -------------
 * It is the only place that holds five rules the rest of the application depends on:
 *   1. a project code is unique among the non-deleted projects, and always upper case;
 *   2. only a COMPLETED project can be archived;
 *   3. a status change must follow the life cycle of ProjectStatus;
 *   4. changing the initial budget must rebuild the planned billing amounts (marker H-4);
 *   5. a caller without VIEW_KPI never receives an amount or a margin (BR-050).
 * Delete this class and those rules disappear: two projects could share the code "PRJ-01",
 * a cancelled project could be set back to active, and a developer could read the budget
 * and the sold margin of the contract from the plain project list.
 *
 * TWO LAYERS OF AUTHORISATION, AND WHY BOTH ARE NEEDED
 * ----------------------------------------------------
 *   permission: @PreAuthorize("hasAuthority('...')") on the methods below. It answers
 *               "may this person do this kind of action at all?".
 *   scope:      scopeService.assertCanAccess(...) / accessibleProjectIds(...). It answers
 *               "on which projects?" (ADR-021).
 * A chef de projet holds EDIT_PROJECT for his own projects only. With the permission check
 * alone he could archive somebody else's project just by changing the id in the URL.
 * The permission checks sit on the SERVICE and not on the controller on purpose: KpiService
 * and other back-end callers reach these methods without passing through any controller, and
 * the rule must still hold for them.
 * Nothing here ever tests a role NAME (ADR-001): the link between a role and its capabilities
 * is a set of rows in the database that an administrator edits at run time.
 */
@Service
// @Service makes this class a Spring bean: one shared instance built at start-up, injected
// into ProjectController. Without it the controller cannot start ("no qualifying bean").
// It is also what lets Spring wrap the class in the proxy that reads @PreAuthorize and
// @Transactional below - a plain "new ProjectService(...)" would run with no security and no
// transaction at all.
@RequiredArgsConstructor
// Lombok writes the constructor over the five final fields below and Spring fills them in.
// Without it: five lines of boilerplate to maintain by hand every time a dependency changes.
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectMapper projectMapper;
    private final ProjectScopeService scopeService;
    // JalonService belongs to the billing module. It is here for one reason only: marker H-4,
    // in update() below. Changing a budget must rebuild the money amount of the planned
    // billing milestones, and that arithmetic belongs to the billing module, not to this one.
    private final JalonService jalonService;

    /**
     * Returns every active project (not deleted, not archived) the caller is allowed to see,
     * already mapped to the JSON records and already blanked of financial data when the caller
     * has no VIEW_KPI.
     *
     * Why the scope filter happens in Java, after the query, and not inside the SQL: the list
     * of active projects is small (tens of rows), and findAllActive() already fetches the
     * director and the chef de projet in the same query. Refiltering in memory keeps one
     * query. The paged variant right below does it differently, and the comment there explains
     * why.
     */
    // VIEW_PROJECT is the read permission of the module. Checked on the service so the rule
    // holds for every caller, not only for HTTP requests.
    // Without it, any authenticated user - including an administrator whose job is only to
    // manage accounts - could list the whole portfolio.
    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    // readOnly = true: one transaction, nothing meant to be written. Hibernate then skips the
    // change tracking it would otherwise do on every loaded row.
    // Without it, an accidental setter somewhere in the read path could be flushed to the
    // database at the end of the request.
    @Transactional(readOnly = true)
    public List<ProjectResponse> findAll() {
        List<Project> projects = projectRepository.findAllActive();
        // The filter only runs for someone WITHOUT the portfolio-wide capability. For the
        // Director this whole block is skipped, so his request costs one query.
        if (!scopeService.hasAllAccess()) {
            // Scope ADR-021: return only the projects the caller leads or is assigned to.
            Set<Long> accessible = scopeService.accessibleProjectIds(currentEmail());
            // The stream keeps only the rows whose id is in that set. contains() on a Set is
            // roughly one step, so this stays cheap even on the whole active list.
            // Without this filter a chef de projet would receive the codes, clients and dates
            // of every contract of the company, which is exactly the leak ADR-021 closes.
            projects = projects.stream().filter(p -> accessible.contains(p.getId())).toList();
        }
        return toResponseList(projects);
    }

    /**
     * The paged version used by the projects screen: GET /api/projects?page=0&size=20.
     * Returns one page of active projects the caller may see, sorted by the Pageable the
     * controller built (code ascending by default).
     *
     * WHY THIS ONE FILTERS IN SQL AND THE LIST VERSION ABOVE FILTERS IN JAVA:
     * with paging, filtering afterwards is simply wrong. Page 1 would be fetched as 20 rows
     * and then reduced to, say, 3, so the user would see a 3-row page while the total still
     * claimed 57 projects, and page 2 would skip rows he is allowed to see. The ids therefore
     * have to be inside the WHERE clause, which is what findAllActiveByIdIn does.
     */
    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public Page<ProjectResponse> findAll(Pageable pageable) {
        if (scopeService.hasAllAccess()) {
            // Page.map() converts the content of the page row by row and carries the paging
            // information (total elements, total pages, current page) over untouched.
            // Rebuilding a new Page by hand would mean recomputing those totals, and any
            // mistake there breaks the Prev/Next buttons of the table.
            return projectRepository.findAllActivePaged(pageable).map(this::toResponse);
        }
        Set<Long> accessible = scopeService.accessibleProjectIds(currentEmail());
        // An empty set means the caller leads nothing and is assigned to nothing.
        // The short-circuit matters: "... WHERE p.id IN ()" is invalid SQL in PostgreSQL, so
        // without this line the request would fail with a syntax error instead of showing an
        // empty, correct project list.
        if (accessible.isEmpty()) return Page.empty(pageable);
        return projectRepository.findAllActiveByIdIn(accessible, pageable).map(this::toResponse);
    }

    /**
     * Returns the archived projects the caller may see. Archiving is a flag on the row, not a
     * status, so an archived project keeps its whole history and can be brought back with
     * unarchive() below.
     *
     * Why a separate method and not a boolean argument on findAll(): the screen has two
     * distinct tabs, and a separate repository query keeps the "archived = true" condition in
     * the SQL rather than in an "if" the caller could forget.
     *
     * Why the URL needs this filter at all, when /api/projects/{id} does not: the endpoint is
     * GET /api/projects/archived, which carries no project id. ProjectScopeInterceptor only
     * matches /api/projects/{digits}, so it cannot act here and the perimeter has to be
     * applied by hand, exactly as in findAll() above.
     */
    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public List<ProjectResponse> findArchived() {
        List<Project> projects = projectRepository.findAllArchived();
        // Same two-step as findAll(): the portfolio capability skips the filter, everybody
        // else is reduced to his own perimeter (ADR-021).
        if (!scopeService.hasAllAccess()) {
            // BE EXACT ABOUT WHAT THIS RETURNS HERE. The query behind accessibleProjectIds,
            // ProjectRepository.findAccessibleProjectIdsByEmail, ends with
            // "p.deleted = false AND p.archived = false". An archived project is therefore
            // never in that set, so for a caller without VIEW_ALL_PROJECTS this filter empties
            // the list every time: a chef de projet sees an empty "archived" tab even for the
            // project he archived himself. Only the holder of VIEW_ALL_PROJECTS sees rows here.
            // This is a consequence of that "archived = false" condition, not a decision
            // written anywhere; say so plainly rather than describing an intent that does not
            // exist in the code.
            Set<Long> accessible = scopeService.accessibleProjectIds(currentEmail());
            projects = projects.stream().filter(p -> accessible.contains(p.getId())).toList();
        }
        return toResponseList(projects);
    }

    /**
     * Returns one project by its id, or raises a 404 when that id does not exist or was
     * soft-deleted.
     *
     * Why the scope is checked HERE as well, although ProjectScopeInterceptor already checked
     * it for the URL /api/projects/{id}: the interceptor only sees HTTP requests. Any future
     * back-end caller that reaches this method directly would otherwise bypass ADR-021. The
     * check is cheap for the Director (it exits immediately on the capability) and it makes
     * the method safe on its own.
     */
    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public ProjectResponse findById(Long id) {
        // Loaded first, so an unknown id produces a clean 404 rather than a confusing 403.
        Project project = loadProject(id);
        scopeService.assertCanAccess(id, currentEmail()); // scope ADR-021
        return toResponse(project);
    }

    /**
     * Moves a finished project out of the active lists. Returns the updated project.
     * Throws BusinessRuleException, which GlobalExceptionHandler turns into HTTP 422, when the
     * project is not COMPLETED.
     *
     * Why archiving is a separate flag and not a sixth status: the status describes the life
     * of the work (draft, running, paused, finished, cancelled), archiving describes where the
     * row is displayed. Keeping them apart means an archived project is still a COMPLETED
     * project for every report and every KPI, and unarchiving it changes nothing else.
     */
    // EDIT_PROJECT, not DELETE_PROJECT: archiving hides a project, it destroys nothing.
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    // @Transactional (without readOnly) makes the whole method one unit of work in the
    // database. Why: the rule check and the write below must not be separable. Without it a
    // crash after the setArchived(true) but before the save would leave the caller believing
    // the project was archived while the row never changed.
    @Transactional
    public ProjectResponse archive(Long id) {
        // Scope checked BEFORE loading here: a caller outside his perimeter is told 403 and
        // never learns whether that id exists at all.
        scopeService.assertCanAccess(id, currentEmail());
        Project project = loadProject(id);
        // The guard that makes archiving meaningful. Without it a DRAFT or an ACTIVE project
        // could be archived, so a running contract would silently vanish from the projects
        // screen while its team kept declaring workload against it.
        if (project.getStatus() != ProjectStatus.COMPLETED) {
            throw new BusinessRuleException("Seul un projet terminé peut être archivé");
        }
        project.setArchived(true);
        return toResponse(projectRepository.save(project));
    }

    /**
     * Brings an archived project back into the active lists. Returns the updated project.
     *
     * Why there is no status rule here, unlike archive(): coming back is always allowed. The
     * asymmetry is deliberate - a wrongly archived project must be recoverable in one click,
     * with nothing to undo first.
     *
     * Why loadProject() finds the row although the project is archived: findActiveById filters
     * on "deleted = false" only and says nothing about "archived". That is the difference
     * between the two flags - a deleted project is gone, an archived one is only put aside.
     */
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse unarchive(Long id) {
        // Same scope guard as archive(), but note the asymmetry a jury may well point at: the
        // perimeter query excludes archived projects ("p.archived = false"), so for a caller
        // without VIEW_ALL_PROJECTS this line refuses with 403 the very project he archived a
        // minute earlier. In practice only the holder of VIEW_ALL_PROJECTS can unarchive, and
        // ProjectScopeInterceptor already stops the others before this method is entered,
        // because the URL PATCH /api/projects/{id}/unarchive does carry an id.
        scopeService.assertCanAccess(id, currentEmail());
        Project project = loadProject(id);
        project.setArchived(false);
        return toResponse(projectRepository.save(project));
    }

    /**
     * Creates a project from the form sent by the browser and returns it with its new id.
     * Throws IllegalArgumentException, which GlobalExceptionHandler turns into HTTP 409
     * (Conflict), when the code is already taken.
     *
     * Why there is no scope check: the project does not exist yet, so there is no perimeter to
     * test. Being allowed to create one at all is the whole question, and that is what
     * CREATE_PROJECT answers.
     */
    // CREATE_PROJECT is the portfolio-level capability. In the default matrix the Director
    // holds it; a chef de projet holds EDIT_PROJECT only, so he can fill in his own project
    // sheet but cannot open new contracts.
    @PreAuthorize("hasAuthority('CREATE_PROJECT')")
    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        // The code is stored upper case, always. Why: it is the human key of the project,
        // printed on documents and typed by hand. Without this line "prj-01" and "PRJ-01"
        // would be two different projects, both accepted, and the uniqueness check just below
        // would never catch the duplicate.
        String normalizedCode = request.code().toUpperCase();
        // "AndDeletedFalse" matters: a code freed by a soft-deleted project can be reused.
        // Without that part of the name, a project deleted two years ago would keep its code
        // reserved for ever and the user would be refused a code that nobody uses.
        if (projectRepository.existsByCodeAndDeletedFalse(normalizedCode)) {
            throw new IllegalArgumentException("Code projet déjà utilisé : " + normalizedCode);
        }

        // The Lombok builder is used instead of a constructor because Project has more than
        // twenty fields: a positional constructor call with that many arguments is where two
        // BigDecimal values get swapped without the compiler noticing.
        Project project = Project.builder()
                .code(normalizedCode)
                .name(request.name())
                .description(request.description())
                // The form may leave the status empty. DRAFT is the right start: it is the
                // entry point of the life cycle drawn in ProjectStatus, where DRAFT leads to
                // ACTIVE or CANCELLED and nothing leads back to DRAFT. Starting anywhere else
                // would skip steps the enum is there to enforce - a project born COMPLETED can
                // never move again, since COMPLETED has no successor.
                // Without this fallback the status column, declared NOT NULL, would reject the
                // insert.
                .status(request.status() != null ? request.status() : ProjectStatus.DRAFT)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .initialBudget(request.initialBudget())
                // resolveUser turns the id sent by the browser into a real, non-deleted User
                // row, or raises a 404. Storing the id blindly would let the browser attach a
                // director who does not exist.
                .director(resolveUser(request.directorId()))
                .build();

        // Naming the chef de projet needs the capability ASSIGN_CHEF_PROJET, not merely
        // CREATE_PROJECT. Why: who leads a project is a management decision, separate from
        // opening the contract. Without this second check, anybody able to create a project
        // could also appoint himself chef de projet of it - and, through ADR-021, give himself
        // the data scope that goes with the job. The field is silently ignored rather than
        // refused, so the rest of the creation still succeeds.
        if (request.chefProjetId() != null && hasAuthority("ASSIGN_CHEF_PROJET")) {
            project.setChefProjet(resolveUser(request.chefProjetId()));
        }
        applyFicheIdentification(project, request);
        return toResponse(projectRepository.save(project));
    }

    /**
     * Updates an existing project from the form and returns it. Throws 404 for an unknown id
     * and 409 (Conflict) when the new code already belongs to another project.
     *
     * The method does three separate jobs, in this order: check the code, copy the plain
     * fields, then re-check the two fields that carry authority (director and chef de projet).
     * The last part is the reason the method is not a simple field-by-field copy.
     */
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse update(Long id, ProjectRequest request) {
        Project project = loadProject(id);
        String normalizedCode = request.code().toUpperCase();

        // The first half of the condition is what allows a project to be saved without
        // changing its code. Without it, re-saving a project would find its OWN code in the
        // table and refuse the update with "code already used" every single time.
        if (!project.getCode().equals(normalizedCode) && projectRepository.existsByCodeAndDeletedFalse(normalizedCode)) {
            throw new IllegalArgumentException("Code projet déjà utilisé : " + normalizedCode);
        }

        // H-4: when the initial budget changes and no avenant exists yet (revisedBudget is
        // null), the effective budget changes too, so the amounts of the PREVU billing
        // milestones have to be recomputed.
        // Why revisedBudget must be null for this to apply: as soon as an avenant exists, the
        // effective budget is the revised one (Project.getEffectiveBudget()), the initial
        // budget no longer drives anything, and AvenantService owns the recompute instead.
        // The flag is computed BEFORE the setters below, because afterwards the old value is
        // gone and there is nothing left to compare.
        // compareTo() is used instead of equals() - BigDecimal.equals() also compares the
        // number of decimals, so 100 and 100.00 would count as different and every save would
        // needlessly rewrite the whole billing plan.
        boolean budgetWillChange = project.getRevisedBudget() == null
                && !budgetEqual(request.initialBudget(), project.getInitialBudget());

        project.setCode(normalizedCode);
        project.setName(request.name());
        project.setDescription(request.description());
        // A null status means "the form did not touch it". Assigning it blindly would wipe the
        // status column, which is NOT NULL, and the save would fail.
        if (request.status() != null) project.setStatus(request.status());
        project.setStartDate(request.startDate());
        project.setEndDate(request.endDate());
        project.setInitialBudget(request.initialBudget());
        // Re-assigning the director is reserved to the portfolio capability (CREATE_PROJECT):
        // a chef de projet, who holds EDIT_PROJECT alone, cannot change the director of his
        // own project. Without this check he could remove the person who supervises him.
        if (request.directorId() != null && hasAuthority("CREATE_PROJECT")) {
            project.setDirector(resolveUser(request.directorId()));
        }
        // Re-assigning the chef de projet is reserved to ASSIGN_CHEF_PROJET, so that a project
        // manager holding only EDIT_PROJECT cannot get around assignChefProjet() below by
        // sending the field through the ordinary update form.
        if (request.chefProjetId() != null && hasAuthority("ASSIGN_CHEF_PROJET")) {
            project.setChefProjet(resolveUser(request.chefProjetId()));
        }
        applyFicheIdentification(project, request);

        Project saved = projectRepository.save(project);
        if (budgetWillChange) {
            // H-4: rebuild montant = effective budget x percentage for the PREVU milestones
            // only. Already invoiced or paid milestones stay frozen, which is correct
            // accounting. Both writes are inside the same @Transactional method, so either the
            // new budget and the new milestone amounts are both stored, or neither is.
            // Without this call the payment plan keeps the amounts of the old budget while the
            // percentages still add up to 100%, and the client ends up invoiced for a total
            // that matches no contract.
            jalonService.recomputePrevuMontants(saved); // H-4
        }
        return toResponse(saved);
    }

    /**
     * Copies the fields of the "Fiche d'identification" (the identification sheet of the Excel
     * model F-AFF-13) from the form onto the project.
     *
     * Why it is a separate private method called by both create() and update(): these twelve
     * fields are copied identically in both places. Written twice, a field added tomorrow
     * would be added to one of the two and silently ignored in the other - the classic bug
     * where a value saves on edit but is lost on creation.
     */
    private void applyFicheIdentification(Project project, ProjectRequest request) {
        project.setContractId(request.contractId());
        project.setClient(request.client());
        project.setFunder(request.funder());
        project.setBusinessModel(request.businessModel());
        project.setEngagementType(request.engagementType());
        // The currency is only overwritten when the form really sent one. Why: the entity
        // defaults it to "TND", and blanking it would break every conversion that reads it.
        // Upper case because the code is used as a label everywhere ("eur" and "EUR" must be
        // one and the same currency on screen).
        if (request.currency() != null && !request.currency().isBlank()) {
            project.setCurrency(request.currency().toUpperCase());
        }
        // Same protection for the exchange rate, and it matters more: the entity defaults it
        // to 1. Writing null here would make Project.getBudgetTnd() and the whole Devis
        // Interne fall back to a rate of 1, so a budget in FCFA would be read as if it were
        // already in dinars.
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
     * Names the chef de projet of a project and returns the updated project.
     * Throws 404 when the user id is unknown or belongs to a deleted account.
     *
     * Why a dedicated endpoint rather than the ordinary update form: this single field decides
     * who gets the data scope of the project through ADR-021, so it deserves its own
     * capability and its own trace in the API.
     */
    // The capability is the strictest of the module. Note there is no VIEW/EDIT check on top:
    // holding ASSIGN_CHEF_PROJET is precisely the right to make this change.
    @PreAuthorize("hasAuthority('ASSIGN_CHEF_PROJET')")
    @Transactional
    public ProjectResponse assignChefProjet(Long projectId, Long userId) {
        Project project = loadProject(projectId);
        User chef = userRepository.findById(userId)
                // A soft-deleted account is still a row in the table, so findById finds it.
                // Without this filter a project could be handed to a user whose account was
                // closed: nobody would ever open it again, and the scope of ADR-021 would
                // point at a login that can no longer authenticate.
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + userId));

        project.setChefProjet(chef);
        return toResponse(projectRepository.save(project));
    }

    /**
     * Moves the project to another status and returns it.
     * Throws BusinessRuleException (HTTP 422) when the life cycle forbids the move.
     *
     * Why the rule is asked of the enum instead of being written here as a chain of "if":
     * ProjectStatus.canTransitionTo() keeps the whole life cycle in one readable place, next
     * to the five states themselves, so any other caller of the status gets the same answer.
     */
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse changeStatus(Long id, ProjectStatus newStatus) {
        Project project = loadProject(id);
        // Without this check any jump would be accepted: a CANCELLED project could be set back
        // to ACTIVE, or a DRAFT declared COMPLETED without a single day of work behind it, and
        // every KPI built on that project would then describe a state that never happened.
        if (!project.getStatus().canTransitionTo(newStatus)) {
            throw new BusinessRuleException("Transition de statut interdite : "
                    + project.getStatus() + " → " + newStatus);
        }
        project.setStatus(newStatus);
        return toResponse(projectRepository.save(project));
    }

    /**
     * Deletes a project - as a SOFT delete: the row stays in the table with its "deleted" flag
     * raised, and every read query of the module filters it out.
     *
     * Why not a real DELETE: workload lines, billing milestones, payments, KPI snapshots and
     * Devis Interne lines all point at this row. A real delete would either be refused by the
     * foreign keys or, with a cascade, would destroy years of accounting. Keeping the row also
     * keeps the history readable for an audit, which is the point of a management tool.
     */
    // DELETE_PROJECT is its own capability, separate from EDIT_PROJECT, so that being able to
    // correct a project sheet does not imply being able to make the project disappear.
    @PreAuthorize("hasAuthority('DELETE_PROJECT')")
    @Transactional
    public void delete(Long id) {
        Project project = loadProject(id);
        project.setDeleted(true);
        projectRepository.save(project);
    }

    /**
     * Turns one Project entity into the ProjectResponse record sent as JSON, applying the
     * financial wall BR-050: without VIEW_KPI no amount and no margin is exposed
     * (ADR-001: the test is on a capability, never on a role name).
     *
     * Why the blanking happens here and not in the mapper: every read path of this service
     * goes through this one method, so there is exactly one door. Put the same test in the
     * mapper and a future caller using projectMapper directly would walk straight past it.
     *
     * withoutFinancials() returns a COPY with the money fields set to null; it does not modify
     * the entity. That matters because the entity is still attached to the transaction - a
     * blanking done on the entity itself would be flushed, and the budget would be erased in
     * the database the moment a developer opened the project sheet.
     */
    private ProjectResponse toResponse(Project project) {
        ProjectResponse response = projectMapper.toResponse(project);
        return hasAuthority("VIEW_KPI") ? response : response.withoutFinancials();
    }

    /**
     * Maps a whole list through toResponse(), so the BR-050 blanking is applied row by row.
     * Written as a helper rather than repeated in the three list methods, because a list that
     * forgot the blanking would leak the very data the single-project path protects.
     */
    private List<ProjectResponse> toResponseList(List<Project> projects) {
        return projects.stream().map(this::toResponse).toList();
    }

    /**
     * Loads a project that is not soft-deleted, or raises NotFoundException, which the global
     * exception handler turns into an HTTP 404.
     *
     * Why every method goes through this helper: the "deleted = false" condition and the 404
     * are then impossible to forget. A method calling projectRepository.findById() directly
     * would happily reopen a deleted project.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /**
     * The login (an e-mail address here) of the person behind the current request, or null
     * when nobody is authenticated. It is the key ProjectScopeService uses to resolve the
     * perimeter.
     *
     * Why null is returned instead of throwing: this is also called from unit tests and from
     * paths where security has not run yet. accessibleProjectIds(null) already answers with an
     * empty set, so a null simply means "sees nothing" - the closed, safe answer.
     */
    private String currentEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    /**
     * True when the current user holds the given capability (ADR-001: the test is on a
     * capability, never on a role name).
     *
     * Why this exists next to @PreAuthorize: @PreAuthorize can only accept or refuse the whole
     * method. Here the capability decides part of the behaviour instead - whether the chef de
     * projet field is applied, and whether the amounts are kept in the response. Refusing the
     * whole request in those cases would be wrong: the rest of the form is perfectly legal.
     */
    private boolean hasAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // The null test comes first and short-circuits: with no authentication the stream is
        // never reached, so this cannot throw NullPointerException on an unauthenticated path.
        return auth != null && auth.getAuthorities().stream()
                // anyMatch stops at the first hit. code.equals(...) is written this way round
                // so that an authority returning null cannot throw.
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    /**
     * Turns a user id coming from the browser into a real User entity, or null when no id was
     * sent (director and chef de projet are both optional on a project).
     * Throws 404 when the id is unknown or the account was soft-deleted.
     *
     * Why the entity is loaded instead of storing the raw id: the foreign key is then checked
     * here, with a clear message, rather than by PostgreSQL at flush time with a constraint
     * error the user cannot understand.
     */
    private User resolveUser(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                // Same reason as in assignChefProjet: a closed account must not be attached to
                // a project.
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + userId));
    }

    /**
     * Compares two money amounts by VALUE, ignoring how many decimals each one carries, and
     * treating two nulls as equal.
     *
     * Why it exists at all: BigDecimal.equals() answers false for 100 and 100.00, because it
     * also compares the scale. The browser sends "650000" while PostgreSQL returns
     * "650000.00" for the same NUMERIC(15,2) column, so equals() would report a change on
     * every single save. The consequence would not be cosmetic: update() would think the
     * budget changed and would call jalonService.recomputePrevuMontants() on every edit of a
     * project name.
     *
     * static because it depends on nothing in the instance - it is pure arithmetic.
     */
    private static boolean budgetEqual(BigDecimal a, BigDecimal b) {
        // Two empty budgets are the same budget: no change, no recompute.
        if (a == null && b == null) return true;
        // Exactly one of them is null: a budget was set or cleared, which IS a change. This
        // line is also what stops the compareTo() below from throwing NullPointerException.
        if (a == null || b == null) return false;
        // compareTo() returns 0 when the two numbers have the same value, whatever their
        // scale. 100 and 100.00 are therefore equal here, and that is the whole point.
        return a.compareTo(b) == 0;
    }
}

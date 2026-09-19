package com.pms.workload.service;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import com.pms.workload.dto.PlanChargeRequest;
import com.pms.workload.dto.PlanChargeResponse;
import com.pms.workload.entity.PlanCharge;
import com.pms.workload.mapper.PlanChargeMapper;
import com.pms.workload.repository.PlanChargeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/*
 * WHAT THIS FILE IS
 * -----------------
 * The business service of the "plan de charge" (the planned workload). One PlanCharge row
 * says: "this person is EXPECTED to work N days on this project during this month". One
 * PlanCharge object = one row of the PostgreSQL table plan_charges, created by migration
 * V7__schema_workload.sql.
 *
 * Planned days are a forecast written by the manager. They are never declared by the
 * person concerned, and they have no approval step - which is the whole difference with
 * the twin file described below.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   Angular workload screen
 *     -> HTTP /api/projects/{projectId}/plan-charges
 *     -> ProjectScopeInterceptor  (ADR-021: may this caller touch THIS project at all?)
 *     -> WorkloadController       (reads the URL + the JSON body, calls this file)
 *     -> PlanChargeService        (THIS FILE: permission + scope of the data + rules)
 *     -> PlanChargeRepository     (the only class that talks to plan_charges)
 *     -> PlanChargeMapper         (PlanCharge entity -> PlanChargeResponse JSON)
 * Three other repositories are used as helpers, never as the main table:
 *   ProjectRepository        - proves the project of the URL exists and is not deleted;
 *   UserRepository           - loads the person the days are planned for, and the caller;
 *   TeamAssignmentRepository - answers "is this person really on this team?" (marker H-8).
 * WorkloadController is the only caller of this class in the whole backend.
 *
 * ITS TWIN FILE, AND HOW THE TWO DIFFER
 * -------------------------------------
 * ChargeReelleService (same folder) is the mirror image of this file: it handles the REAL
 * days instead of the planned ones. They look alike on purpose but are deliberately NOT
 * merged, because the rules are genuinely different:
 *   - here EVERY write needs VALIDATE_WORKLOAD, because planning is a manager's decision;
 *     over there writing needs SUBMIT_WORKLOAD, because a developer declares his own days;
 *   - here there is no approval step and therefore no "frozen once validated" rule, so no
 *     isValidated() check appears anywhere below;
 *   - and for the same reason there is no BR-033 ownership guard here: a manager plans for
 *     other people by definition, so "you may only act on your own rows" would make the
 *     feature impossible. BR-033 only exists in the timesheet file.
 * Merging the two into one generic service would mean an "if" on the type inside every
 * method, which is exactly the kind of branching that hides a security rule.
 *
 * WHY IT EXISTS - what would be missing if you deleted it
 * -------------------------------------------------------
 * The planned days are one of the two inputs of the whole cost calculation. KpiService
 * calls planChargeRepository.findActiveByProjectId(projectId) for the plan and
 * chargeReelleRepository.findValidatedByProjectId(projectId) for the reality, multiplies
 * both by the daily rate of each person, and compares them. Without the plan there is
 * nothing to compare the real days against: the "planned versus real" follow-up, the
 * forecast cost and the EVM indicators all lose their reference point.
 * Delete this class and the controller would have to call the repository directly: the
 * permission check, the H-8 membership check, the frozen period and the frozen user would
 * all disappear, and the deletes would become real SQL deletes, so the planning history
 * of a project would be lost.
 *
 * THREE PROTECTIONS, NOT ONE - the point a jury often asks about
 * --------------------------------------------------------------
 * 1. PERMISSION: @PreAuthorize("hasAuthority('...')") on the methods below. It answers
 *    "is this caller allowed to do this KIND of thing anywhere?".
 * 2. PROJECT SCOPE (ADR-021): ProjectScopeInterceptor answers "is this caller allowed on
 *    THIS project?" before the controller method is even entered. The permission alone is
 *    not enough - every manager holds VALIDATE_WORKLOAD, so without the interceptor one of
 *    them could plan the team of a colleague's project.
 * 3. ROW SCOPE (BR-062...064): inside a reachable project, canSeeAllWorkload() below
 *    answers "which ROWS may he see?".
 * None of the three is ever a role name (ADR-001): the permission codes come from the
 * database table role_permissions, so an administrator can move VALIDATE_WORKLOAD to
 * another role while the application is running, with no rebuild.
 *
 * HTTP CODES PRODUCED FROM HERE (GlobalExceptionHandler does the translation)
 * --------------------------------------------------------------------------
 *   NotFoundException        -> 404 Not Found            (unknown project / plan row / user)
 *   BusinessRuleException    -> 422 Unprocessable Entity (a rule refuses the operation)
 *   IllegalArgumentException -> 409 Conflict             (a duplicate row - marker M-3
 *                                                         keeps those two apart)
 */

// @Service registers this class as a Spring bean: one shared instance is built at startup
// and injected into WorkloadController.
// It is also what lets Spring wrap the bean in a proxy object, and that proxy is what
// actually applies @PreAuthorize and @Transactional on the methods below.
// Without it: the application refuses to start with "no qualifying bean of type
// PlanChargeService", and a hand-made `new PlanChargeService(...)` would run with no
// permission check and no transaction at all.
@Service
// Lombok writes, at compile time, a constructor taking the five `final` fields below, and
// Spring uses that single constructor to inject them.
// Why constructor injection rather than @Autowired on each field: the fields stay final,
// so nothing can swap a repository at runtime, and a test can build the service with mock
// objects in one line.
// Without it: only the empty constructor would exist, the five fields would stay null, and
// the very first call would end in a NullPointerException.
@RequiredArgsConstructor
public class PlanChargeService {

    // The five collaborators, injected once at startup. `final` means each one is set by
    // the constructor and can never be replaced, so no later code can silently point this
    // service at a different table.
    //   planChargeRepository     - reads/writes plan_charges, always soft-delete aware;
    //   projectRepository        - only to prove the project of the URL exists (404);
    //   userRepository           - loads the target person, and the logged-in caller;
    //   planChargeMapper         - MapStruct-generated translator entity -> response DTO
    //                              (DTO = Data Transfer Object: a small object built only
    //                              to travel to the browser, so the database shape never
    //                              leaks into the API);
    //   teamAssignmentRepository - the H-8 membership question, borrowed from the team
    //                              module: "does this person really work on this project?".
    private final PlanChargeRepository planChargeRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final PlanChargeMapper planChargeMapper;
    private final TeamAssignmentRepository teamAssignmentRepository;

    /**
     * Lists the planned rows of one project as a plain list (no paging).
     *
     * WHAT IT GIVES BACK
     * Every non-deleted row of the project when the caller has the broad view, otherwise
     * only the rows planned for the caller himself (BR-062...064).
     *
     * WHY WRITTEN THIS WAY
     * The choice between the two queries is made BEFORE the database call, and the user
     * filter is pushed INTO the SQL (findActiveByProjectIdAndUserId), not applied with a
     * stream after loading everything. Filtering in memory would read the plan of the whole
     * team into the server first - the data would still leave the database - and, in the
     * paged twin method below, it would also break the total count shown on the screen.
     *
     * WORTH KNOWING: no code in the backend calls this overload today; the controller uses
     * the Pageable one below. It is kept because it is the shape another service would need
     * (a whole year at once, with no page cutting), and because it states the same scope
     * rule in its simplest form.
     */
    // Reading the plan needs VIEW_WORKLOAD, the same code as reading the timesheet: for the
    // user both are one screen, so splitting them into two permissions would only mean two
    // ways to get half a screen. Spring reads this annotation because @EnableMethodSecurity
    // is set on SecurityConfig; the codes themselves were put into the security context by
    // JwtAuthenticationFilter, which read them from the database table role_permissions.
    // Why a permission code and never a role name (ADR-001): the role-to-permission matrix
    // lives in the database and an administrator can change it at runtime from the Roles
    // screen. Writing hasRole('CHEF_PROJET') would freeze that matrix inside the Java code
    // and make the admin screen a lie.
    // Without this line: any authenticated account could read the planning of any project it
    // can reach - and the plan shows how the manager intends to staff the months ahead.
    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    // Runs the whole method inside ONE read-only database transaction.
    // Why: the entities must stay attached to the Hibernate session while the mapper walks
    // into pc.getUser() and pc.getProject(); and Hibernate skips the change tracking it
    // would do in a writable transaction.
    // Without it: application.yml sets open-in-view: false, so the session would already be
    // closed when the mapper runs, and a lazy link would blow up with
    // LazyInitializationException - a 500 error on a screen that looked fine.
    @Transactional(readOnly = true)
    public List<PlanChargeResponse> findByProject(Long projectId) {
        // Called for its side effect only: it throws NotFoundException (404) when the project
        // id of the URL does not exist or was soft-deleted. The returned Project is not
        // needed here.
        // Why bother: the list query alone would return an empty list for an unknown project,
        // and the screen would say "nothing planned yet" instead of "no such project".
        loadProject(projectId);
        // BR-062...064: does this caller get the whole team's plan, or only his own lines?
        if (canSeeAllWorkload()) {
            return planChargeMapper.toResponseList(planChargeRepository.findActiveByProjectId(projectId));
        }
        // The narrow path. currentUserId() gives -1 when the caller cannot be resolved, which
        // matches no row: the failure mode is "you see nothing", never "you see everything".
        return planChargeMapper.toResponseList(
                planChargeRepository.findActiveByProjectIdAndUserId(projectId, currentUserId()));
    }

    /**
     * The paged version of the method above - this is the one the workload screen calls.
     *
     * WHAT IT GIVES BACK
     * A Page: one slice of rows (20 by default, newest period first - those defaults come
     * from @PageableDefault on WorkloadController) plus the TOTAL number of rows, which is
     * what lets the screen draw "page 2 of 7".
     *
     * WHY WRITTEN THIS WAY
     * The same broad/narrow choice as above, but here it matters twice over. The two
     * repository methods each carry their own countQuery, so the total is counted with the
     * same WHERE clause as the rows. If the own-only filter were applied in Java after the
     * query, a developer would be told "61 results" and then be shown his single row, page
     * after empty page.
     */
    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    @Transactional(readOnly = true)
    public Page<PlanChargeResponse> findByProject(Long projectId, Pageable pageable) {
        // Same 404 guard as the overload above.
        loadProject(projectId);
        if (canSeeAllWorkload()) {
            // .map(...) on a Page converts only the rows of THIS slice into DTOs and keeps
            // the paging information (page number, size, total) untouched.
            // Why a method reference (planChargeMapper::toResponse) rather than
            // toResponseList: Page has no list-level mapper, and rebuilding a Page by hand
            // from a List would lose the total count the screen needs.
            return planChargeRepository.findActiveByProjectIdPaged(projectId, pageable)
                    .map(planChargeMapper::toResponse);
        }
        return planChargeRepository.findActiveByProjectIdAndUserIdPaged(projectId, currentUserId(), pageable)
                .map(planChargeMapper::toResponse);
    }

    /**
     * Plans the days one person is expected to spend on one project during one month.
     *
     * WHAT IT GIVES BACK
     * The freshly created row as a PlanChargeResponse, so the screen can show it with its
     * new id without asking the server again.
     *
     * WHY WRITTEN THIS WAY
     * There is no ownership guard here, unlike in ChargeReelleService.submit(): planning for
     * OTHER people is precisely what this method is for, and the right to do it is already
     * carried by the VALIDATE_WORKLOAD permission plus the ADR-021 project scope. The only
     * guard about WHO the row is for is H-8 below.
     *
     * The row is created, never silently updated when the month already has one: the
     * duplicate is refused, so a second manager cannot overwrite a forecast without seeing
     * that one already existed.
     */
    // VALIDATE_WORKLOAD, the manager's capability, guards all three writes of this file.
    // Reusing the validation code rather than inventing a MANAGE_PLAN one is a deliberate
    // "coarsening": planning and validating are the same job done by the same person
    // (default matrix of migration V12: only CHEF_PROJET holds it - the Director reads with
    // VIEW_WORKLOAD + VIEW_ALL_PROJECTS but does not plan).
    // Without this line: a developer holding only SUBMIT_WORKLOAD could write his own
    // forecast, and the plan the KPI compares the real days against would stop being the
    // manager's decision.
    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    // @Transactional makes this whole method one single database unit of work.
    // Why: the duplicate check and the INSERT must not be split, and if assertTeamMembership
    // throws after something was written, nothing is kept.
    // Without it: each repository call would run in its own auto-commit transaction, and a
    // crash between two of them would leave the database half updated.
    @Transactional
    public PlanChargeResponse create(Long projectId, PlanChargeRequest request) {
        // 404 if the project is unknown or soft-deleted. The Project object IS used here: it
        // carries the chef de projet that assertTeamMembership needs, and it becomes the
        // foreign key of the new row.
        Project project = loadProject(projectId);
        // 404 if the id points to nobody, or to a soft-deleted account.
        // Note the order compared with the twin file: here the user is loaded BEFORE the
        // membership check, so an unknown id answers 404 while a known non-member answers
        // 422. The two answers are both correct, only their order differs.
        User user = loadUser(request.userId());

        // H-8: the target user must be an active member of the project team (or its chef de
        // projet). It is checked here, at creation, because update() freezes the user of a
        // row afterwards - so the membership never has to be re-checked later.
        // Without it: a manager could plan days for anyone in the company onto a project he
        // leads - an accountant, a developer of another team - and the forecast cost of the
        // project would be built on people who will never work on it.
        assertTeamMembership(project, request.userId());

        // The screen sends a year and a month; the column stores a real DATE. The convention
        // of the whole workload module - written in migration V7 as the comment "toujours le
        // 1er du mois" (always the 1st of the month) - is to normalise every period to day 1.
        // Why a DATE and not two integer columns: it can be compared, sorted and used in a
        // BETWEEN directly, which is what the KPI queries need, and it matches the timesheet
        // table exactly so the two can be joined month by month.
        // Without the normalisation: March planned on the 1st and March planned on the 15th
        // would be two different periods, the duplicate check below would not see them as the
        // same month, and neither would the unique index uk_pc_active.
        LocalDate period = LocalDate.of(request.year(), request.month(), 1);

        // One person can have only ONE planned row per project per month. The check is
        // limited to live rows (AndDeletedFalse), so a row deleted by mistake does not block
        // a new forecast for the same month.
        // IMPORTANT - this check is not the real protection. Between this answer and the
        // INSERT, a second request could insert the same triplet. What makes a double row
        // impossible is the partial unique index of migration V7:
        //   CREATE UNIQUE INDEX uk_pc_active ON plan_charges(project_id, user_id, period)
        //     WHERE deleted = FALSE;
        // "Partial" means only the live rows are covered, which is what allows the delete +
        // re-plan case above. The two work together: the index guarantees correctness, this
        // check guarantees a readable message in the normal case.
        if (planChargeRepository.existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(projectId, request.userId(), period)) {
            // IllegalArgumentException and not BusinessRuleException on purpose (marker M-3):
            // a duplicate is a data CONFLICT and maps to HTTP 409, while a refused rule maps
            // to 422. The split lets the Angular side tell "this month is already planned,
            // open it" apart from "this operation is not allowed".
            throw new IllegalArgumentException(
                    "Une charge planifiée existe déjà pour cet utilisateur sur cette période");
        }

        // The Lombok @Builder of the entity. Why a builder rather than a constructor with
        // four arguments in a row: project and user are both entity references and period is
        // a date, so a positional constructor would make it easy to swap two values of the
        // same type without the compiler noticing.
        // Nothing else is set: unlike a timesheet row, a planned row has no submittedAt, no
        // validatedAt and no validatedBy - it is a forecast, not a declaration, so it is
        // complete as soon as it is written.
        PlanCharge pc = PlanCharge.builder()
                .project(project)
                .user(user)
                .period(period)
                .plannedDays(request.plannedDays())
                .build();

        // save() gives back the entity carrying its generated id; that returned instance is
        // what gets mapped, so the response holds the id the screen needs.
        return planChargeMapper.toResponse(planChargeRepository.save(pc));
    }

    /**
     * Changes the number of planned days of an existing row.
     *
     * WHAT IT GIVES BACK
     * The updated row as a PlanChargeResponse.
     *
     * WHY WRITTEN THIS WAY - two things are deliberately frozen
     * Only plannedDays can really change. The period and the user of an existing row are
     * compared with the request and refused when they differ (422), instead of being copied
     * over. Reason: allowing them to change would turn "correct the March forecast of Ali"
     * into "move it onto Sonia in June", which would walk straight around the H-8 membership
     * check done at creation, and would also let a row slide onto a month that already has
     * its own row, which the unique index uk_pc_active would then reject with a raw database
     * error instead of a clear message. Freezing the user is what makes it safe NOT to run
     * assertTeamMembership again here.
     *
     * There is no "frozen once validated" rule here, unlike in the timesheet file: a
     * forecast has no approval step and stays editable for as long as the project runs.
     */
    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public PlanChargeResponse update(Long projectId, Long id, PlanChargeRequest request) {
        // 404 if the id is unknown or the row was soft-deleted.
        PlanCharge pc = loadPlanCharge(id);

        // The row was looked up by its own id, so nothing yet proves it belongs to the
        // project named in the URL. This line proves it.
        // Why 404 and not 403: answering "forbidden" would confirm that plan row 87 exists
        // somewhere else. Answering "not found" tells an attacker nothing.
        // Without it: a manager in scope on project 7 could call /api/projects/7/... with the
        // id of a row of project 9 - ProjectScopeInterceptor only checked 7 - and rewrite the
        // planning of a project he does not lead.
        if (!pc.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge planifiée introuvable : " + id);
        }

        // Rebuilt from the request the same way as in create(), then compared. The request is
        // not trusted to repeat the right month: if it names another one, the operation is
        // refused rather than silently moved.
        LocalDate expectedPeriod = LocalDate.of(request.year(), request.month(), 1);
        if (!pc.getPeriod().equals(expectedPeriod)) {
            // 422: the request is well formed, it is the rule that refuses it.
            throw new BusinessRuleException("La période d'une charge planifiée ne peut pas être modifiée");
        }
        // Same idea for the person. This is the line that makes re-checking H-8 unnecessary
        // here: the person attached to the row can never change, so a planned row can never
        // end up on somebody who is not on the team.
        if (!pc.getUser().getId().equals(request.userId())) {
            throw new BusinessRuleException("L'utilisateur d'une charge planifiée ne peut pas être modifié");
        }

        // The single field that may really change.
        pc.setPlannedDays(request.plannedDays());
        // `pc` is already attached to the open transaction, so Hibernate would write the
        // change at commit even without this call (it is called "dirty checking"). save() is
        // kept because it is explicit and because it returns the instance to map.
        return planChargeMapper.toResponse(planChargeRepository.save(pc));
    }

    /**
     * Removes a planned row - as a SOFT delete.
     *
     * WHAT IT GIVES BACK
     * Nothing; the controller answers 204 No Content.
     *
     * WHY WRITTEN THIS WAY
     * Nothing is erased: the boolean column `deleted` (inherited from BaseEntity) is set to
     * true and the row stays in the table. Two reasons. First, the planning history of a
     * project has to stay readable - the forecast that was made and then dropped is part of
     * how the project was steered. Second, every query of this module already filters on
     * deleted = false and the unique index uk_pc_active is partial (WHERE deleted = FALSE),
     * so the same month can be planned again afterwards without the old row blocking the new
     * one - which a real DELETE would make impossible to tell apart from "it never happened".
     *
     * WORTH KNOWING FOR THE DEFENCE: there is no isValidated() guard here, unlike in
     * ChargeReelleService.delete(). Nothing in this table is ever validated, so there is
     * nothing to freeze. Removing a planned row does change what the KPI compare against,
     * which is why the operation stays behind VALIDATE_WORKLOAD and the ADR-021 scope.
     */
    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public void delete(Long projectId, Long id) {
        PlanCharge pc = loadPlanCharge(id);

        // Same cross-project proof as in update(), for the same reason.
        if (!pc.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge planifiée introuvable : " + id);
        }

        // The soft delete itself: one boolean, no SQL DELETE.
        pc.setDeleted(true);
        planChargeRepository.save(pc);
    }

    /**
     * BR-062...064: the broad view (the plan of the whole team) is reserved to holders of
     * VALIDATE_WORKLOAD (the chef de projet, who does the planning) or of VIEW_ALL_PROJECTS
     * (the director). Anyone else sees only his own plan.
     * Checked by capability, never by role name (ADR-001).
     *
     * WHY THE SECOND CAPABILITY IS NEEDED
     * The obvious version would test VALIDATE_WORKLOAD alone. It would be wrong: in the
     * default matrix the Director holds VIEW_WORKLOAD WITHOUT VALIDATE_WORKLOAD, so he would
     * have been restricted to his own rows - and a director has none, so his workload screen
     * would have been empty on every project. VIEW_ALL_PROJECTS is his existing scope-lift
     * capability (ADR-021), so it is reused instead of inventing a new permission and a new
     * migration.
     *
     * WHY THIS EXISTS AT ALL
     * ProjectScopeService says WHICH PROJECTS are reachable; this says WHICH ROWS are
     * readable inside one reachable project. Without it, a developer assigned to a project
     * would read how many days the manager has planned for every one of his colleagues -
     * exactly the gap the 2026-07-16 audit found on the read paths.
     *
     * The method is the byte-for-byte twin of the one in ChargeReelleService. They are kept
     * apart rather than pulled into a shared helper because each one is the scope rule OF
     * ITS OWN table: if one of the two tables ever needs a different rule, the change must
     * not silently apply to the other.
     */
    private boolean canSeeAllWorkload() {
        return hasAuthority("VALIDATE_WORKLOAD") || hasAuthority("VIEW_ALL_PROJECTS");
    }

    /**
     * Answers "does the logged-in caller carry this permission code?".
     *
     * WHY WRITTEN THIS WAY
     * SecurityContextHolder is a store tied to the current thread: it holds the Authentication
     * that JwtAuthenticationFilter put there for THIS request, so no method needs the caller
     * passed down as an argument.
     * The authorities it contains are permission codes (VIEW_WORKLOAD, VALIDATE_WORKLOAD...),
     * not role names - the filter read them from role_permissions. That is why this one
     * method can be reused everywhere: the code never branches on a role (ADR-001).
     * The null check on auth is needed because this class is also reachable outside an HTTP
     * request (a unit test, a future scheduled job) where nobody filled the context; without
     * it those calls would fail with a NullPointerException instead of simply answering "no".
     */
    private boolean hasAuthority(String code) {
        // A stream with anyMatch stops at the first match instead of building an intermediate
        // collection. It is written code.equals(a.getAuthority()) and not the other way round
        // so that an authority returning null cannot throw.
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    /**
     * Id of the current user; -1 when he cannot be resolved (no line comes back then).
     *
     * WHY -1 RATHER THAN null OR AN EXCEPTION
     * The value is fed straight into a WHERE user.id = :userId. A null would make that
     * comparison match nothing in a way that depends on the SQL dialect, while -1 is an id no
     * BIGSERIAL ever produces, so the query provably returns an empty page. The failure mode
     * is "you see nothing", never "you see everything" - the safe direction for a security
     * filter.
     *
     * HOW IT IS BUILT
     * The token carries the email, not the database id: auth.getName() returns the principal,
     * which JwtAuthenticationFilter set to the email string. .map(User::getId) reaches into
     * the Optional only when an account was found, and .orElse(-1L) supplies the safe value
     * otherwise - so the "not found" case never needs a null check of its own.
     * findActiveByEmailWithRole also excludes soft-deleted accounts, so a still-valid token
     * belonging to a deactivated user lands on -1 and reads nothing.
     */
    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return -1L;
        return userRepository.findActiveByEmailWithRole(auth.getName())
                .map(User::getId)
                .orElse(-1L);
    }

    /**
     * H-8: the target user must be an active member of the project team (or its chef de
     * projet).
     *
     * WHAT IT DOES
     * Returns quietly when days may be planned for this person on this project; throws
     * BusinessRuleException (422) otherwise.
     *
     * WHY WRITTEN THIS WAY
     * The chef de projet exemption is the decision H-8 asked to encode: leading a project
     * counts as working on it, and a chef de projet very often has no row in
     * team_assignments. Testing it FIRST also avoids a useless database call for him.
     * The null check on getChefProjet() is not decoration: a project can legitimately be
     * created before a manager is named, and calling .getId() on null would turn a clean 422
     * into a 500.
     * The membership question itself is delegated to TeamAssignmentRepository rather than
     * duplicated here, so "who is on this team" has exactly one definition in the whole
     * application (ChargeReelleService and BacklogItemService call the very same method).
     */
    private void assertTeamMembership(Project project, Long targetUserId) {
        if (project.getChefProjet() != null && project.getChefProjet().getId().equals(targetUserId)) {
            return; // the chef de projet is implicitly a member of his own project
        }
        // existsBy... answers true or false without loading any row into memory: PostgreSQL
        // stops at the first match. AndDeletedFalse matters here - leaving a team is a soft
        // delete, so a member who was removed must count as absent.
        if (!teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(project.getId(), targetUserId)) {
            throw new BusinessRuleException(
                    "L'utilisateur n'est pas membre actif de l'équipe de ce projet");
        }
    }

    /**
     * Loads one live planned row by its own id, or throws 404.
     *
     * WHY WRITTEN THIS WAY
     * findActiveById adds "AND deleted = false" and JOIN FETCHes the project and the user in
     * the SAME SQL query, so the mapper can read both without one extra SELECT each - and
     * without a LazyInitializationException once the transaction is closed.
     * Optional + orElseThrow is used rather than returning null so that a missing row is an
     * honest 404 instead of a NullPointerException turned into a 500.
     */
    private PlanCharge loadPlanCharge(Long id) {
        return planChargeRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Charge planifiée introuvable : " + id));
    }

    /**
     * Loads one live project by id, or throws 404.
     *
     * WHY WRITTEN THIS WAY
     * Every public method of this class starts from a project id taken from the URL. Doing
     * the existence check in one private place gives the same 404 message everywhere, and it
     * cannot be forgotten in a new method written by copy-paste.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /**
     * Loads one user by id, refusing soft-deleted accounts, or throws 404.
     *
     * WHY WRITTEN THIS WAY
     * findById is the built-in JpaRepository method and knows nothing about soft delete, so
     * the filter is added here: .filter(u -> !u.isDeleted()) turns an Optional holding a
     * deleted account into an empty Optional, which then becomes the same 404 as an unknown
     * id.
     * Without that filter: a forecast could be written for a person who left the company and
     * whose account was deactivated, and that person would reappear on the workload screen
     * and inside the planned cost of the project.
     */
    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}

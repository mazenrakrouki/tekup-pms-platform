package com.pms.workload.service;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import com.pms.workload.dto.ChargeReelleRequest;
import com.pms.workload.dto.ChargeReelleResponse;
import com.pms.workload.entity.ChargeReelle;
import com.pms.workload.mapper.ChargeReelleMapper;
import com.pms.workload.repository.ChargeReelleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/*
 * WHAT THIS FILE IS
 * -----------------
 * The business service of the "charges reelles" (actual workload - the timesheet of the
 * application). One ChargeReelle row says: "this person really worked N days on this
 * project during this month". One ChargeReelle object = one row of the PostgreSQL table
 * charges_reelles, created by migration V7__schema_workload.sql.
 *
 * A row lives through two steps:
 *   1. SUBMIT   - the developer declares his days   (submittedAt is filled);
 *   2. VALIDATE - the manager approves them         (validatedAt + validatedBy filled).
 * Only a VALIDATED row counts as a real cost (see "WHY IT EXISTS" below).
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   Angular workload screen
 *     -> HTTP /api/projects/{projectId}/charges-reelles
 *     -> ProjectScopeInterceptor  (ADR-021: may this caller touch THIS project at all?)
 *     -> WorkloadController       (reads the URL + the JSON body, calls this file)
 *     -> ChargeReelleService      (THIS FILE: permission + scope of the data + rules)
 *     -> ChargeReelleRepository   (the only class that talks to charges_reelles)
 *     -> ChargeReelleMapper       (ChargeReelle entity -> ChargeReelleResponse JSON)
 * Three other repositories are used as helpers, never as the main table:
 *   ProjectRepository        - proves the project of the URL exists and is not deleted;
 *   UserRepository           - loads the person the days belong to, and the caller;
 *   TeamAssignmentRepository - answers "is this person really on this team?" (marker H-8).
 * WorkloadController is the only caller of this class in the whole backend.
 *
 * ITS TWIN FILE, AND HOW THE TWO DIFFER
 * -------------------------------------
 * PlanChargeService (same folder) is the mirror image of this file: it handles the
 * PLANNED days instead of the real ones. The two look alike on purpose, but they are
 * deliberately NOT merged, because the rules are not the same:
 *   - planning is written by the manager only (VALIDATE_WORKLOAD on every write), while a
 *     timesheet is written by the developer himself (SUBMIT_WORKLOAD);
 *   - a plan row has no approval step; a timesheet row has submittedAt, validatedAt and
 *     validatedBy, and freezes once approved.
 * Merging them into one generic service would mean an "if" on the type inside every
 * method, which is exactly the kind of branching that hides a security rule.
 *
 * WHY IT EXISTS - what would be missing if you deleted it
 * -------------------------------------------------------
 * The numbers produced here are the cost side of the whole project. KpiService calls
 * chargeReelleRepository.findValidatedByProjectId(projectId) and multiplies the days by
 * the daily rate of each person to obtain the consumed budget, the margin and the EVM
 * indicators. So every guard written below protects a figure that ends up on the
 * director's dashboard:
 *   - BR-033 (a developer may only act on HIS OWN rows) stops somebody from inflating a
 *     colleague's timesheet;
 *   - H-8 (the person must be on the team) stops days being charged to a project the
 *     person never worked on;
 *   - the "already validated is frozen" rule stops an approved cost from being rewritten
 *     after the fact;
 *   - BR-062...064 (read scope) stops a developer from reading what his colleagues
 *     declared.
 * Delete this class and the controller would have to call the repository directly: none
 * of those four rules would run, and the deletes would become real SQL deletes, so the
 * audit trail of the declared days would disappear.
 *
 * THREE PROTECTIONS, NOT ONE - the point a jury often asks about
 * --------------------------------------------------------------
 * 1. PERMISSION: @PreAuthorize("hasAuthority('...')") on the methods below. It answers
 *    "is this caller allowed to do this KIND of thing anywhere?".
 * 2. PROJECT SCOPE (ADR-021): ProjectScopeInterceptor answers "is this caller allowed on
 *    THIS project?" before the controller method is even entered. The permission alone is
 *    not enough.
 * 3. ROW SCOPE (BR-062...064): inside a reachable project, canSeeAllWorkload() at the
 *    bottom of this file answers "which ROWS may he see?".
 * None of the three is ever a role name (ADR-001): the permission codes come from the
 * database table role_permissions, so an administrator can move VALIDATE_WORKLOAD to
 * another role while the application is running, with no rebuild.
 *
 * HTTP CODES PRODUCED FROM HERE (GlobalExceptionHandler does the translation)
 * --------------------------------------------------------------------------
 *   NotFoundException        -> 404 Not Found            (unknown project / charge / user)
 *   BusinessRuleException    -> 422 Unprocessable Entity (a rule refuses the operation)
 *   IllegalArgumentException -> 409 Conflict             (a duplicate row - marker M-3
 *                                                         keeps those two apart)
 *   AccessDeniedException    -> 403 Forbidden            (BR-033 violated)
 */

// @Service registers this class as a Spring bean: one shared instance is built at startup
// and injected into WorkloadController.
// It is also what lets Spring wrap the bean in a proxy object, and that proxy is what
// actually applies @PreAuthorize and @Transactional on the methods below.
// Without it: the application refuses to start with "no qualifying bean of type
// ChargeReelleService", and a hand-made `new ChargeReelleService(...)` would run with no
// permission check and no transaction at all.
@Service
// Lombok writes, at compile time, a constructor taking the five `final` fields below, and
// Spring uses that single constructor to inject them.
// Why constructor injection rather than @Autowired on each field: the fields stay final,
// so nothing can swap a repository at runtime, and a unit test (ChargeReelleServiceTest)
// can build the service with mock objects in one line.
// Without it: only the empty constructor would exist, the five fields would stay null, and
// the very first call would end in a NullPointerException.
@RequiredArgsConstructor
public class ChargeReelleService {

    // The five collaborators, injected once at startup. `final` means each one is set by
    // the constructor and can never be replaced, so no later code can silently point this
    // service at a different table.
    //   chargeReelleRepository   - reads/writes charges_reelles, always soft-delete aware;
    //   projectRepository        - only to prove the project of the URL exists (404);
    //   userRepository           - loads the target person, and the logged-in caller;
    //   chargeReelleMapper       - MapStruct-generated translator entity -> response DTO
    //                              (DTO = Data Transfer Object: a small object built only
    //                              to travel to the browser, so the database shape never
    //                              leaks into the API);
    //   teamAssignmentRepository - the H-8 membership question, borrowed from the team
    //                              module: "does this person really work on this project?".
    private final ChargeReelleRepository chargeReelleRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ChargeReelleMapper chargeReelleMapper;
    private final TeamAssignmentRepository teamAssignmentRepository;

    /**
     * Lists the timesheet rows of one project as a plain list (no paging).
     *
     * WHAT IT GIVES BACK
     * Every non-deleted row of the project when the caller has the broad view, otherwise
     * only the rows that belong to the caller himself (BR-062...064).
     *
     * WHY WRITTEN THIS WAY
     * The choice between the two queries is made BEFORE the database call, and the user
     * filter is pushed INTO the SQL (findActiveByProjectIdAndUserId), not applied with a
     * stream after loading everything. Filtering in memory would read the rows of the whole
     * team into the server first - the data would still leave the database - and, in the
     * paged twin method below, it would also break the total count shown on the screen.
     *
     * WORTH KNOWING: no code in the backend calls this overload today; the controller uses
     * the Pageable one below. It is kept because it is the shape another service would need
     * (a whole year at once, with no page cutting), and because it states the same scope
     * rule in its simplest form.
     */
    // Requires the VIEW_WORKLOAD permission code. Spring reads this annotation because
    // @EnableMethodSecurity is set on SecurityConfig; the codes themselves were put into
    // the security context by JwtAuthenticationFilter, which read them from the database
    // table role_permissions.
    // Why a permission code and never a role name (ADR-001): the role-to-permission matrix
    // lives in the database and an administrator can change it at runtime from the Roles
    // screen. Writing hasRole('DEVELOPPEUR') would freeze that matrix inside the Java code
    // and make the admin screen a lie.
    // Without this line: any authenticated account could read the declared days of any
    // project it can reach.
    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    // Runs the whole method inside ONE read-only database transaction.
    // Why: the entities must stay attached to the Hibernate session while the mapper walks
    // into cr.getUser() and cr.getValidatedBy(); and Hibernate skips the change tracking it
    // would do in a writable transaction.
    // Without it: application.yml sets open-in-view: false, so the session would already be
    // closed when the mapper runs, and a lazy link would blow up with
    // LazyInitializationException - a 500 error on a screen that looked fine.
    @Transactional(readOnly = true)
    public List<ChargeReelleResponse> findByProject(Long projectId) {
        // Called for its side effect only: it throws NotFoundException (404) when the
        // project id of the URL does not exist or was soft-deleted. The returned Project is
        // not needed here.
        // Why bother: the list query alone would return an empty list for an unknown
        // project, and the screen would say "no workload yet" instead of "no such project".
        loadProject(projectId);
        // BR-062...064: does this caller get the whole team's rows, or only his own?
        if (canSeeAllWorkload()) {
            return chargeReelleMapper.toResponseList(chargeReelleRepository.findActiveByProjectId(projectId));
        }
        // The narrow path. currentUserId() gives -1 when the caller cannot be resolved,
        // which matches no row: the failure mode is "you see nothing", never "you see
        // everything".
        return chargeReelleMapper.toResponseList(
                chargeReelleRepository.findActiveByProjectIdAndUserId(projectId, currentUserId()));
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
    public Page<ChargeReelleResponse> findByProject(Long projectId, Pageable pageable) {
        // Same 404 guard as the overload above.
        loadProject(projectId);
        if (canSeeAllWorkload()) {
            // .map(...) on a Page converts only the rows of THIS slice into DTOs and keeps
            // the paging information (page number, size, total) untouched.
            // Why a method reference (chargeReelleMapper::toResponse) rather than
            // toResponseList: Page has no list-level mapper, and rebuilding a Page by hand
            // from a List would lose the total count the screen needs.
            return chargeReelleRepository.findActiveByProjectIdPaged(projectId, pageable)
                    .map(chargeReelleMapper::toResponse);
        }
        return chargeReelleRepository.findActiveByProjectIdAndUserIdPaged(projectId, currentUserId(), pageable)
                .map(chargeReelleMapper::toResponse);
    }

    /**
     * Records the days one person really worked on one project during one month.
     *
     * WHAT IT GIVES BACK
     * The freshly created row as a ChargeReelleResponse, so the screen can show it with its
     * new id without asking the server again.
     *
     * WHY WRITTEN THIS WAY - the order of the four guards is deliberate
     *   1. loadProject          -> 404 if the project does not exist (cheapest check, and
     *                              it is also what gives assertTeamMembership the chef de
     *                              projet of that project);
     *   2. assertOwnership      -> 403, BR-033, before anything is read about the target;
     *   3. assertTeamMembership -> 422, H-8, before the user row is loaded;
     *   4. duplicate check      -> 409.
     * Answering "you are not allowed" before "that user does not exist" also avoids
     * telling a developer whether a given user id exists.
     *
     * The row is created, never silently updated when the month already has one: the
     * duplicate is refused, because overwriting would destroy the submittedAt / validatedAt
     * history that the cost calculation depends on.
     */
    // SUBMIT_WORKLOAD is the developer's own capability (default matrix of migration V12:
    // DEVELOPPEUR holds SUBMIT_WORKLOAD, CHEF_PROJET holds VALIDATE_WORKLOAD). Holding it is
    // not enough on its own: ADR-021 already refused the request upstream if the project is
    // outside the caller's perimeter, and assertOwnership below decides WHOSE days may be
    // declared.
    @PreAuthorize("hasAuthority('SUBMIT_WORKLOAD')")
    // @Transactional makes this whole method one single database unit of work.
    // Why: the duplicate check and the INSERT must not be split, and if assertTeamMembership
    // throws after something was written, nothing is kept.
    // Without it: each repository call would run in its own auto-commit transaction, and a
    // crash between two of them would leave the database half updated.
    @Transactional
    public ChargeReelleResponse submit(Long projectId, ChargeReelleRequest request) {
        // 404 if the project is unknown or soft-deleted. The Project object IS used here: it
        // carries the chef de projet that assertTeamMembership needs, and it becomes the
        // foreign key of the new row.
        Project project = loadProject(projectId);

        // BR-033: only a validator (a caller who also holds VALIDATE_WORKLOAD) may submit for
        // somebody else. A plain developer may only declare his own days.
        // Without it: any holder of SUBMIT_WORKLOAD could type days in the name of a
        // colleague, and that colleague's timesheet - plus the project cost built on it -
        // would be wrong with no trace of who did it.
        assertOwnership(request.userId());

        // H-8: the target user must be an active member of the project team (or its chef de
        // projet). It is checked here, at submission, because update() freezes the user of a
        // row afterwards - so the membership never has to be re-checked later.
        // Without it: a validator could book days for anyone in the company onto any project
        // inside his perimeter, and the cost attribution of the project would be corrupted.
        assertTeamMembership(project, request.userId());

        // 404 if the id points to nobody, or to a soft-deleted account.
        User user = loadUser(request.userId());
        // The screen sends a year and a month; the column stores a real DATE. The convention
        // of the whole workload module - written in migration V7 as the comment "toujours le
        // 1er du mois" (always the 1st of the month) - is to normalise every period to day 1.
        // Why a DATE and not two integer columns: it can be compared, sorted and used in a
        // BETWEEN directly, which is what the KPI queries need.
        // Without the normalisation: March declared on the 1st and March declared on the 15th
        // would be two different periods, the duplicate check below would not see them as the
        // same month, and neither would the unique index uk_cr_active.
        LocalDate period = LocalDate.of(request.year(), request.month(), 1);

        // One person can have only ONE timesheet row per project per month. The check is
        // limited to live rows (AndDeletedFalse), so a row deleted by mistake does not block
        // a new declaration for the same month.
        // IMPORTANT - this check is not the real protection. Between this answer and the
        // INSERT, a second request could insert the same triplet. What makes a double row
        // impossible is the partial unique index of migration V7:
        //   CREATE UNIQUE INDEX uk_cr_active ON charges_reelles(project_id, user_id, period)
        //     WHERE deleted = FALSE;
        // "Partial" means only the live rows are covered, which is what allows the delete +
        // re-declare case above. The two work together: the index guarantees correctness,
        // this check guarantees a readable message in the normal case.
        if (chargeReelleRepository.existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(projectId, request.userId(), period)) {
            // IllegalArgumentException and not BusinessRuleException on purpose (marker M-3):
            // a duplicate is a data CONFLICT and maps to HTTP 409, while a refused rule maps
            // to 422. The split lets the Angular side tell "this month is already declared,
            // open it" apart from "this operation is not allowed".
            throw new IllegalArgumentException(
                    "Une charge réelle existe déjà pour cet utilisateur sur cette période");
        }

        // The Lombok @Builder of the entity. Why a builder rather than a constructor with
        // five arguments in a row: ChargeReelle has two more nullable fields (validatedAt,
        // validatedBy) that must stay empty at this point, and a positional constructor would
        // make it easy to swap two values of the same type without the compiler noticing.
        // Note what is NOT set here: validatedAt and validatedBy stay null, which is exactly
        // what isValidated() reads - so a brand-new row is "submitted, not yet approved" and
        // does not count as a cost until validate() runs.
        ChargeReelle cr = ChargeReelle.builder()
                .project(project)
                .user(user)
                .period(period)
                .actualDays(request.actualDays())
                // The moment of the declaration, kept apart from createdAt (inherited from
                // BaseEntity): update() below refreshes submittedAt but never createdAt, so
                // the two together say "first written then, last declared now".
                .submittedAt(LocalDateTime.now())
                .build();

        // save() gives back the entity carrying its generated id; that returned instance is
        // what gets mapped, so the response holds the id the screen needs.
        return chargeReelleMapper.toResponse(chargeReelleRepository.save(cr));
    }

    /**
     * Corrects the number of days of a row that has NOT been approved yet.
     *
     * WHAT IT GIVES BACK
     * The updated row as a ChargeReelleResponse.
     *
     * WHY WRITTEN THIS WAY - three things are deliberately frozen
     * Only actualDays can really change. The period and the user of an existing row are
     * compared with the request and refused when they differ (422), instead of being copied
     * over. Reason: allowing them to change would turn "edit my January row" into "move my
     * January row onto my colleague in March", which would walk straight around both BR-033
     * and the H-8 membership check done at submission. Freezing the user is also what makes
     * it safe NOT to run assertTeamMembership again here.
     * The third frozen thing is an approved row: once validated it is a cost, so it is
     * read-only.
     *
     * MARKER C-2: this method used to have no BR-033 guard at all - any holder of
     * SUBMIT_WORKLOAD could rewrite a teammate's unvalidated days. The assertOwnership call
     * below is the fix, and it is the very same private method submit() uses, so the two
     * paths can no longer drift apart.
     */
    @PreAuthorize("hasAuthority('SUBMIT_WORKLOAD')")
    @Transactional
    public ChargeReelleResponse update(Long projectId, Long id, ChargeReelleRequest request) {
        // 404 if the id is unknown or the row was soft-deleted.
        ChargeReelle cr = loadChargeReelle(id);

        // The row was looked up by its own id, so nothing yet proves it belongs to the
        // project named in the URL. This line proves it.
        // Why 404 and not 403: answering "forbidden" would confirm that charge 87 exists
        // somewhere else. Answering "not found" tells an attacker nothing.
        // Without it: a caller in scope on project 7 could call /api/projects/7/... with the
        // id of a row of project 9 - ProjectScopeInterceptor only checked 7 - and edit data
        // of a project he cannot even see.
        if (!cr.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge réelle introuvable : " + id);
        }

        // isValidated() is simply "validatedAt != null" on the entity. Once the manager has
        // approved the days, they are part of the consumed budget read by KpiService.
        // Without this guard: a developer could get his days approved and then rewrite them,
        // silently changing the cost, the margin and the EVM indicators of the project after
        // the manager signed them off.
        if (cr.isValidated()) {
            throw new BusinessRuleException("Impossible de modifier une charge déjà validée");
        }

        // BR-033: a developer may only modify his OWN charges - the same rule as at
        // submission, and on purpose the very same method. Note the argument: the owner of
        // the EXISTING row, not the one in the request body, because the request body is
        // exactly what an attacker controls.
        assertOwnership(cr.getUser().getId());

        // Rebuilt from the request the same way as in submit(), then compared. The request is
        // not trusted to repeat the right month: if it names another one, the operation is
        // refused rather than silently moved.
        LocalDate expectedPeriod = LocalDate.of(request.year(), request.month(), 1);
        if (!cr.getPeriod().equals(expectedPeriod)) {
            // 422: the request is well formed, it is the rule that refuses it.
            throw new BusinessRuleException("La période d'une charge réelle ne peut pas être modifiée");
        }
        // Same idea for the owner. This is the line that makes re-checking H-8 unnecessary
        // here: the person attached to the row can never change, so a row can never end up on
        // somebody who is not on the team.
        if (!cr.getUser().getId().equals(request.userId())) {
            throw new BusinessRuleException("L'utilisateur d'une charge réelle ne peut pas être modifié");
        }

        // The single field that may really change.
        cr.setActualDays(request.actualDays());
        // The declaration date is refreshed: the row is being declared again, now. Note it is
        // not reset to null - a corrected row is still a submitted row.
        cr.setSubmittedAt(LocalDateTime.now());
        // `cr` is already attached to the open transaction, so Hibernate would write the
        // change at commit even without this call (it is called "dirty checking"). save() is
        // kept because it is explicit and because it returns the instance to map.
        return chargeReelleMapper.toResponse(chargeReelleRepository.save(cr));
    }

    /**
     * Approves a submitted row: from this moment its days become a real cost of the project.
     *
     * WHAT IT GIVES BACK
     * The row with validatedAt and validatedBy filled in.
     *
     * WHY WRITTEN THIS WAY
     * The validator is NOT taken from the request body: it is resolved from the email of the
     * authenticated caller, which the controller passes down through @AuthenticationPrincipal
     * (the principal of the token is the email string, set by JwtAuthenticationFilter). If
     * the body could name the validator, anybody could sign an approval under a colleague's
     * name, and validatedBy - the only trace of who approved a cost - would be worthless.
     *
     * @param validatorEmail the email of the logged-in caller, never a value chosen by the
     *                       client side
     */
    // VALIDATE_WORKLOAD is the manager's capability. In the default matrix only CHEF_PROJET
    // holds it; the Director deliberately does not (he reads with VIEW_WORKLOAD +
    // VIEW_ALL_PROJECTS but does not approve).
    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public ChargeReelleResponse validate(Long projectId, Long id, String validatorEmail) {
        ChargeReelle cr = loadChargeReelle(id);

        // Same "does this row really belong to the project of the URL?" proof as in update(),
        // for the same reason.
        if (!cr.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge réelle introuvable : " + id);
        }

        // Approving twice is refused rather than ignored. Why it matters: a silent second
        // approval would overwrite validatedAt and validatedBy, and the audit trail would
        // name the last person who clicked instead of the one who really took the decision.
        if (cr.isValidated()) {
            throw new BusinessRuleException("Cette charge est déjà validée");
        }

        // The same query used at login: it loads the account by email, ignores soft-deleted
        // ones, and fetches its role.
        // Why look the user up at all instead of storing the email: validated_by is a real
        // foreign key to users(id) (migration V7), so the trace survives a rename or an email
        // change. Failing with 404 here is a safety net for a token that outlived the account
        // it names.
        User validator = userRepository.findActiveByEmailWithRole(validatorEmail)
                .orElseThrow(() -> new NotFoundException("Validateur introuvable"));

        // These two lines together are what turns the row into a cost: KpiService reads
        // findValidatedByProjectId(...), whose SQL selects on validatedAt IS NOT NULL.
        // Why the approval is required before the days become money: a submitted timesheet is
        // a claim, not a fact. Without this step a developer could change the consumed budget
        // and the margin of a project on his own, just by typing a number.
        cr.setValidatedAt(LocalDateTime.now());
        cr.setValidatedBy(validator);
        return chargeReelleMapper.toResponse(chargeReelleRepository.save(cr));
    }

    /**
     * Removes a timesheet row that was submitted by mistake - as a SOFT delete.
     *
     * WHAT IT GIVES BACK
     * Nothing; the controller answers 204 No Content.
     *
     * WHY WRITTEN THIS WAY
     * Nothing is erased: the boolean column `deleted` (inherited from BaseEntity) is set to
     * true and the row stays in the table. Two reasons. First, the declared-days history of a
     * project has to stay readable for the audit. Second, every query of this module already
     * filters on deleted = false and the unique index uk_cr_active is partial (WHERE deleted
     * = FALSE), so the same person can declare that month again afterwards without the old
     * row blocking the new one - which a real DELETE would make impossible to tell apart from
     * "it never happened".
     *
     * WORTH KNOWING FOR THE DEFENCE: deleting needs VALIDATE_WORKLOAD, not SUBMIT_WORKLOAD.
     * So with the default matrix a developer corrects his own mistake through update(), and
     * only the manager can remove the row entirely. That asymmetry is deliberate, not an
     * oversight: removing a line makes it disappear from the project cost, which is a
     * manager's decision.
     */
    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public void delete(Long projectId, Long id) {
        ChargeReelle cr = loadChargeReelle(id);

        // Same cross-project proof as in update() and validate().
        if (!cr.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge réelle introuvable : " + id);
        }

        // An approved row is part of the consumed budget, so it cannot be made to disappear
        // either. Without this guard, a validated cost could be taken out of the project
        // total with nothing on the screen showing it.
        if (cr.isValidated()) {
            throw new BusinessRuleException("Impossible de supprimer une charge déjà validée");
        }

        // The soft delete itself: one boolean, no SQL DELETE.
        cr.setDeleted(true);
        chargeReelleRepository.save(cr);
    }

    /**
     * Loads one live timesheet row by its own id, or throws 404.
     *
     * WHY WRITTEN THIS WAY
     * findActiveById adds "AND deleted = false" and JOIN FETCHes the project, the user and
     * (with a LEFT JOIN, because it may be null) the validator in the SAME SQL query, so the
     * mapper can read all three without one extra SELECT each - and without a
     * LazyInitializationException once the transaction is closed.
     * Optional + orElseThrow is used rather than returning null so that a missing row is an
     * honest 404 instead of a NullPointerException turned into a 500.
     */
    private ChargeReelle loadChargeReelle(Long id) {
        return chargeReelleRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Charge réelle introuvable : " + id));
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
     * Without that filter: days could be booked on a person who left the company and whose
     * account was deactivated, and that person would reappear on the workload screen.
     */
    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }

    /**
     * H-8: the target user must be an active member of the project team (or its chef de
     * projet).
     *
     * WHAT IT DOES
     * Returns quietly when the person may receive days on this project; throws
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
     * application (PlanChargeService and BacklogItemService call the very same method).
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
     * BR-062...064: the broad view (the workload of the whole team) is reserved to holders of
     * VALIDATE_WORKLOAD (the chef de projet, who has to approve it) or of VIEW_ALL_PROJECTS
     * (the director, portfolio supervision). Anyone else sees only his own charges.
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
     * saw every line of every colleague on it - which is exactly what the 2026-07-16 audit
     * found: one developer account could read 60 entries belonging to 5 other people.
     */
    private boolean canSeeAllWorkload() {
        return hasAuthority("VALIDATE_WORKLOAD") || hasAuthority("VIEW_ALL_PROJECTS");
    }

    /**
     * Id of the current user; -1 when he cannot be resolved (no charge comes back then).
     *
     * WHY -1 RATHER THAN null OR AN EXCEPTION
     * The value is fed straight into a WHERE user.id = :userId. A null would make that
     * comparison match nothing in a way that depends on the SQL dialect, while -1 is an id no
     * BIGSERIAL ever produces, so the query provably returns an empty page. The failure mode
     * is "you see nothing", never "you see everything" - the safe direction for a security
     * filter.
     */
    private Long currentUserId() {
        User current = currentUser();
        return current != null ? current.getId() : -1L;
    }

    /**
     * BR-033: a developer (a caller without VALIDATE_WORKLOAD) may only act on his own
     * charges.
     *
     * WHAT IT DOES
     * Returns quietly when the caller may act on the rows of targetUserId; throws
     * AccessDeniedException (403) otherwise.
     *
     * WHY WRITTEN THIS WAY
     * The escape hatch is the capability VALIDATE_WORKLOAD, not a role name: a caller trusted
     * to APPROVE somebody's days is trusted to enter them for him (a manager filling in for
     * someone on holiday). With the default matrix CHEF_PROJET has no SUBMIT_WORKLOAD, so
     * nobody reaches submit() through that branch today - but the branch is not dead code,
     * because an administrator can grant both capabilities to one role from the Roles screen,
     * which is the whole point of dynamic RBAC (ADR-001).
     * The `current == null` case is treated as a refusal, not as a pass: a caller who cannot
     * be resolved must never be allowed to write in someone else's name.
     * Marker N-5: submit() used to repeat this test inline; both paths now call this one
     * method, so they can no longer drift apart.
     */
    private void assertOwnership(Long targetUserId) {
        if (!hasAuthority("VALIDATE_WORKLOAD")) {
            User current = currentUser();
            if (current == null || !current.getId().equals(targetUserId)) {
                throw new AccessDeniedException("Un développeur ne peut agir que sur ses propres charges");
            }
        }
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
     * Loads the full User row of the logged-in caller, or null.
     *
     * WHY WRITTEN THIS WAY
     * The token carries the email, not the database id: auth.getName() returns the principal,
     * which JwtAuthenticationFilter set to the email string. The numeric id is what has to be
     * compared with the owner of a charge, so one lookup is unavoidable.
     * It returns null rather than throwing, because the two callers want different reactions
     * to the same situation: currentUserId() turns it into "-1, see nothing", assertOwnership()
     * turns it into "403, write nothing". Throwing here would force one behaviour on both.
     * findActiveByEmailWithRole also excludes soft-deleted accounts, so a still-valid token
     * belonging to a deactivated user resolves to null - and both callers fail closed.
     */
    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        return userRepository.findActiveByEmailWithRole(auth.getName()).orElse(null);
    }
}

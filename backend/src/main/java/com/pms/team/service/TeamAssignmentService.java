package com.pms.team.service;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.team.dto.TeamAssignmentRequest;
import com.pms.team.dto.TeamAssignmentResponse;
import com.pms.team.entity.TeamAssignment;
import com.pms.team.mapper.TeamAssignmentMapper;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/* =====================================================================================
 * FILE: TeamAssignmentService.java  --  module "EQUIPE" (team)
 *
 * WHAT THIS FILE IS
 *   The one and only business layer for team assignments: who works on which project,
 *   with which role in the team, and between which dates. It reads, creates, edits and
 *   removes rows of the table "team_assignments" (created by the Flyway migration V6).
 *
 * WHERE IT SITS IN THE FLOW
 *   TeamController  ->  THIS CLASS  ->  repositories + mapper  ->  PostgreSQL
 *
 *   Who calls it: TeamController, on five URLs
 *       GET    /api/projects/{projectId}/team        -> findByProject
 *       POST   /api/projects/{projectId}/team        -> assign
 *       PUT    /api/projects/{projectId}/team/{id}   -> update
 *       DELETE /api/projects/{projectId}/team/{id}   -> remove
 *       GET    /api/users/{userId}/assignments       -> findByUser
 *
 *   What it calls next:
 *       TeamAssignmentRepository -> its own rows (all queries filter deleted = false)
 *       ProjectRepository        -> checks the project exists and is not soft-deleted
 *       UserRepository           -> checks the person exists and is not soft-deleted
 *       TeamAssignmentMapper     -> MapStruct mapper that turns a TeamAssignment entity
 *                                   into a TeamAssignmentResponse DTO. A DTO ("Data
 *                                   Transfer Object") is a small flat object sent to the
 *                                   browser as JSON; we send DTOs and not entities so the
 *                                   JSON never drags along JPA relations or internal
 *                                   columns such as deleted, created_by or updated_by.
 *
 * WHY IT EXISTS
 *   Delete this file and the controller would have to talk to three repositories itself.
 *   The permission checks, the "already a member" rule, the date rule and the logical
 *   delete would then live in the web layer, and every future caller (a batch job, a
 *   second controller, a test) would have to copy them. One of the copies would drift,
 *   and a project would end up with two active rows for the same developer.
 *
 * SECURITY MODEL  (ADR-001 dynamic permissions + ADR-021 project scope)
 *   Two INDEPENDENT checks protect the project URLs above:
 *     1. THE PERMISSION - checked here, by @PreAuthorize on each public method. A
 *        permission is a row in the database granted to a role, never a hard-coded role
 *        name, so an administrator can change who may do what without a new deployment.
 *     2. THE SCOPE - checked before the controller by ProjectScopeInterceptor, which is
 *        registered on /api/projects/** in WebMvcConfig. It answers the other question:
 *        "is this project inside the perimeter of this user?" and returns 403 if not.
 *   ADR-021 says the permission ALONE IS NOT ENOUGH. A project manager holds
 *   ASSIGN_DEVELOPER, but only for the projects he leads; without the scope check he
 *   could staff a project of another manager just by changing the id in the URL.
 * ===================================================================================== */

/**
 * Business rules for adding, editing, listing and removing team members of a project.
 *
 * <p>Design note - why a Spring bean and not a class of static helper methods: Spring wraps
 * this bean in a proxy, and it is that proxy which applies {@code @PreAuthorize} and
 * {@code @Transactional}. Static methods cannot be proxied, so the permission check and the
 * transaction would simply never run.
 *
 * <p>{@code @Service} marks the class so that Spring's component scan creates ONE shared
 * instance and injects it into TeamController. Without it the controller would fail to start
 * with "no qualifying bean of type TeamAssignmentService".
 *
 * <p>{@code @RequiredArgsConstructor} is Lombok: it generates, at compile time, the
 * constructor taking the four {@code final} fields below. Spring then injects them through
 * that constructor. Why constructor injection rather than {@code @Autowired} on the fields:
 * the fields can be {@code final}, so they can never be null and can never be replaced while
 * the application runs, and a unit test can build the service with four mocks without
 * starting any Spring context at all.
 */
@Service
@RequiredArgsConstructor
public class TeamAssignmentService {

    // The four collaborators, injected once at startup (see @RequiredArgsConstructor above).
    // The service keeps no other state: it is stateless, so the single shared instance can
    // serve many HTTP requests at the same time without two users seeing each other's data.
    private final TeamAssignmentRepository teamAssignmentRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TeamAssignmentMapper teamAssignmentMapper;

    /**
     * Lists the CURRENT members of one project (UC-013 "View team").
     * Gives back a list of {@code TeamAssignmentResponse}, one per active member; the list is
     * empty when the project exists but has nobody on it yet.
     *
     * <p>Why {@code loadProject(projectId)} on the first line although its result is not used:
     * it is an existence check. It turns an unknown project id into a clean 404. Without it,
     * GET /api/projects/999999/team would answer "200 OK []" and the front end would draw a
     * normal, empty team page for a project that was never created - the user would think the
     * team is empty instead of understanding that the link is wrong.
     */
    // @PreAuthorize is evaluated by the Spring Security proxy BEFORE the method body runs. The
    // caller must carry the VIEW_TEAM permission; if not, an AccessDeniedException is thrown and
    // GlobalExceptionHandler turns it into HTTP 403. It sits on the SERVICE, not on the
    // controller (ADR-001), so the rule still applies when another service calls this method
    // instead of the web layer. Without it, any authenticated user could read the full staff
    // list of every project in the company.
    // @Transactional(readOnly = true) puts the two reads below (the project check, then the
    // member list) inside one single read-only database transaction. Why: both reads then see
    // the same snapshot of the database, and Hibernate is told it will not write, so it skips
    // the change-detection and the flush it normally does at the end. Without it, the project
    // could be read as existing and, a moment later, the member list read after somebody else
    // deleted that project - two answers that contradict each other in one response.
    @PreAuthorize("hasAuthority('VIEW_TEAM')")
    @Transactional(readOnly = true)
    public List<TeamAssignmentResponse> findByProject(Long projectId) {
        loadProject(projectId);
        // findActiveByProjectId uses "JOIN FETCH ta.user JOIN FETCH ta.project": the member, his
        // project and the assignment all come back in ONE SQL query. Why it matters: the mapper
        // below reads user.getFullName(), project.getCode() and project.getName() for every row.
        // Without the JOIN FETCH, those relations are LAZY: Hibernate would send one query for the
        // list, then one more query per row to load that member's user row. A team of 12 people
        // would mean 12 extra queries (the project itself is loaded only once, because all the
        // rows of this list point at the same project). That is the classic "N+1" problem, and it
        // makes the team page slower every time somebody joins the project.
        return teamAssignmentMapper.toResponseList(teamAssignmentRepository.findActiveByProjectId(projectId));
    }

    /**
     * Lists every current assignment of ONE PERSON, across all projects. This is what feeds the
     * "the projects I work on" view behind GET /api/users/{userId}/assignments.
     * Gives back one response row per active assignment, empty if the person is on no project.
     *
     * <p>{@code loadUser(userId)} first, for the same reason as above: an unknown or deactivated
     * user must produce 404, not an empty list that reads like "this person works on nothing".
     *
     * <p>SCOPE NOTE (ADR-021). This method is reached through /api/users/{userId}/assignments.
     * That URL does NOT match /api/projects/**, so ProjectScopeInterceptor is not registered on
     * it and does not run. The VIEW_TEAM permission below is therefore the only check performed
     * on this path.
     */
    // Same permission as findByProject: reading a team is one single capability, VIEW_TEAM,
    // whichever way round the question is asked (by project, or by person).
    // @Transactional(readOnly = true): same reason as above - one consistent read-only unit of
    // work for the user check plus the assignment list.
    @PreAuthorize("hasAuthority('VIEW_TEAM')")
    @Transactional(readOnly = true)
    public List<TeamAssignmentResponse> findByUser(Long userId) {
        loadUser(userId);
        // findActiveByUserId also JOIN FETCHes user and project, so the mapper can fill
        // projectCode and projectName without firing one extra query per project (N+1).
        return teamAssignmentMapper.toResponseList(teamAssignmentRepository.findActiveByUserId(userId));
    }

    /**
     * Puts one person on one project (UC-012 "Assign developer") and gives back the assignment
     * that was created, with its new database id.
     *
     * <p>The order of the four steps matters: load the project, load the person, refuse a
     * duplicate, refuse impossible dates. Loading the two ends first means a request naming a
     * project or a user that no longer exists fails with a readable 404, instead of reaching the
     * INSERT and coming back as a raw PostgreSQL foreign-key error (HTTP 500).
     */
    // ASSIGN_DEVELOPER, not VIEW_TEAM: looking at a team and changing it are two different
    // capabilities, so a developer can see his team-mates without being able to staff the
    // project. The default grant is DIRECTOR + PM (ADR-005 / D4). Without this line, any user
    // holding only VIEW_TEAM could add himself to any project.
    // @Transactional (read-write this time) makes the whole method one single database unit of
    // work. Why: the duplicate check and the INSERT belong together, and the INSERT also writes
    // the audit columns through JPA auditing. If save() failed, a partially written row must not
    // survive; with the transaction, everything is rolled back and the project keeps the team it
    // had before the call.
    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
    @Transactional
    public TeamAssignmentResponse assign(Long projectId, TeamAssignmentRequest request) {
        // Load the two ends of the link as real entities, not just ids. Why: the new row needs a
        // Project and a User object to be attached to, and loading them proves both still exist
        // and are not soft-deleted before anything is written.
        Project project = loadProject(projectId);
        User user = loadUser(request.userId());

        // Refuse a SECOND ACTIVE membership of the same person on the same project. This repeats
        // in Java the partial unique index of migration V6:
        //     CREATE UNIQUE INDEX uk_ta_project_user_active
        //         ON team_assignments(project_id, user_id) WHERE deleted = FALSE;
        // Why do it twice: the Java test produces a readable message, while the index stays the
        // real guarantee if two requests arrive at the very same instant and both pass this test.
        // "...AndDeletedFalse" is also what makes re-hiring possible: a member removed last month
        // is only soft-deleted, the index ignores that row, so the same person can be assigned
        // again. Without this check the user would get a naked database constraint error instead
        // of a sentence he can understand.
        if (teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(projectId, request.userId())) {
            // IllegalArgumentException is this project's convention for a DATA CONFLICT (a
            // duplicate). GlobalExceptionHandler maps it to HTTP 409 Conflict - to be
            // distinguished from BusinessRuleException below, which maps to 422.
            // The message is in French, the language of the application: "the user is already a
            // member of this project".
            throw new IllegalArgumentException("L'utilisateur est déjà membre de ce projet");
        }

        // An end date placed before the start date makes no sense. endDate is OPTIONAL (an
        // open-ended assignment leaves it null), so the null test MUST come first: calling
        // isBefore() on a null endDate would throw NullPointerException and answer 500 instead of
        // a clean business error. This mirrors the CHECK constraint chk_ta_dates of V6
        // (end_date IS NULL OR end_date >= start_date): Java gives the clear message, the CHECK
        // protects the table against anything that writes to it outside this service.
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            // BusinessRuleException -> HTTP 422 Unprocessable Entity: the request is well formed
            // and the user is allowed, it is the business rule that says no.
            // French message: "the end date cannot be earlier than the start date".
            throw new BusinessRuleException("La date de fin ne peut pas être antérieure à la date de début");
        }

        // Lombok's @Builder on the entity: each value is set BY NAME. Why this and not a
        // constructor: startDate and endDate are both LocalDate, so swapping them in a positional
        // constructor would still compile and would silently create assignments that end before
        // they start. The builder only covers the fields declared in TeamAssignment; the inherited
        // BaseEntity fields are deliberately left out - id is generated by the database
        // (BIGSERIAL / @GeneratedValue), created_at, updated_at, created_by and updated_by are
        // filled by JPA auditing, and deleted defaults to false.
        TeamAssignment ta = TeamAssignment.builder()
                .project(project)
                .user(user)
                .roleInTeam(request.roleInTeam())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .build();

        // save() performs the INSERT and returns the saved entity, now carrying the id that
        // PostgreSQL generated. The mapper then flattens it into the response DTO, which the
        // controller needs immediately: it builds the "Location" header of the 201 Created answer
        // from created.id().
        return teamAssignmentMapper.toResponse(teamAssignmentRepository.save(ta));
    }

    /**
     * Changes an existing assignment and gives back its new state: the role in the team, the
     * start date and the end date.
     *
     * <p>Important: the PERSON is not changed. {@code request.userId()} is not read by this
     * method; only the role and the two dates are written back. Sending another userId therefore
     * changes nothing. To move the work to somebody else, remove this assignment and create a
     * new one - which is also what keeps the history readable, because the removed row stays in
     * the table.
     *
     * <p>Same permission as {@code assign}: editing the role or the period of a member is part
     * of building the team, so it is covered by ASSIGN_DEVELOPER and not by a separate one.
     */
    // @Transactional: the entity loaded below becomes "managed" by Hibernate for the whole
    // method. Without the transaction the three setters would be applied to a detached object and
    // the UPDATE would never be sent, so the API would answer 200 OK with the new values while
    // the database still holds the old ones - the worst kind of bug, a silent one.
    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
    @Transactional
    public TeamAssignmentResponse update(Long projectId, Long assignmentId, TeamAssignmentRequest request) {
        TeamAssignment ta = loadAssignment(assignmentId);

        // ADR-021, the second half of the rule, applied inside the service.
        // ProjectScopeInterceptor has already checked that this caller may touch the {projectId}
        // written in the URL. But nothing so far ties assignmentId TO that project - the id comes
        // from a different part of the URL and could name any row of the table. This line is that
        // tie. Without it, a project manager whose perimeter is project 7 could send
        //     PUT /api/projects/7/team/999
        // where assignment 999 belongs to project 12, and edit the team of a project he is not
        // even allowed to look at: the URL passes the scope check (project 7 is his) and the
        // permission check (he does hold ASSIGN_DEVELOPER).
        // Answering 404 rather than 403 also avoids confirming that assignment 999 exists.
        if (!ta.getProject().getId().equals(projectId)) {
            // French message: "assignment not found: <id>".
            throw new NotFoundException("Affectation introuvable : " + assignmentId);
        }

        // Same date rule as in assign(), and for the same reason: the check is repeated here
        // because an edit can break a period that was valid when it was first created (for
        // example moving only the start date to a day after the end date). The null test comes
        // first because endDate is optional - without it, clearing the end date would throw
        // NullPointerException instead of being accepted.
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            // French message: "the end date cannot be earlier than the start date".
            throw new BusinessRuleException("La date de fin ne peut pas être antérieure à la date de début");
        }

        // The three fields a caller is allowed to change. Note that endDate is set even when it
        // is null: that is how an assignment is turned back into an open-ended one.
        ta.setRoleInTeam(request.roleInTeam());
        ta.setStartDate(request.startDate());
        ta.setEndDate(request.endDate());

        // save() is written explicitly although the entity is managed and Hibernate would detect
        // the change on its own at the end of the transaction. Keeping it makes the write visible
        // to whoever reads the method, and it returns the entity that the mapper flattens into
        // the response sent back with 200 OK.
        return teamAssignmentMapper.toResponse(teamAssignmentRepository.save(ta));
    }

    /**
     * Takes a member off a project (UC-012 "remove developer"). Returns nothing; the controller
     * answers 204 No Content.
     *
     * <p>This is a LOGICAL delete, also called a soft delete: the row stays in the table and only
     * its {@code deleted} flag is set to true. Why not a real SQL DELETE: BR-020 requires the
     * history to be kept. Past assignments are the justification of the work already charged to
     * the project, so erasing them would silently rewrite that past and an old report would stop
     * matching its own data. The partial unique index of V6 only looks at rows where
     * {@code deleted = FALSE}, so the soft-deleted row does not block assigning the same person
     * to the same project again later.
     */
    // ASSIGN_DEVELOPER covers the removal too: in the authorization matrix REMOVE_DEVELOPER was
    // folded into ASSIGN_DEVELOPER, because whoever builds a team is also the one who unbuilds it.
    // @Transactional: the entity must stay managed so the flag change reaches the database.
    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
    @Transactional
    public void remove(Long projectId, Long assignmentId) {
        TeamAssignment ta = loadAssignment(assignmentId);

        // Exactly the same ADR-021 tie as in update(), and it matters even more here because the
        // action destroys something. Without this line, DELETE /api/projects/7/team/999 would
        // remove a member of project 12 while the scope check only ever looked at project 7.
        if (!ta.getProject().getId().equals(projectId)) {
            // French message: "assignment not found: <id>".
            throw new NotFoundException("Affectation introuvable : " + assignmentId);
        }

        // The soft delete itself. The row keeps its role, its dates and its audit columns; every
        // read method of this service filters on deleted = false, so the person disappears from
        // the team screens at once while the history stays queryable in SQL.
        ta.setDeleted(true);
        teamAssignmentRepository.save(ta);
    }

    // ---------------------------------------------------------------------------------------
    // Private loaders. They exist so that the four public methods above never repeat the same
    // "find it, or answer 404" code. Each one returns a real entity and NEVER null: the caller
    // can use the result straight away without testing for null.
    // ---------------------------------------------------------------------------------------

    /**
     * Loads one assignment that has not been soft-deleted, or fails with 404.
     *
     * <p>{@code findActiveById} adds "AND ta.deleted = false" to the query. Why not the standard
     * {@code findById}: that one would happily return a row that was already removed from the
     * team, and a caller could then edit, or remove a second time, a membership that no longer
     * exists for the rest of the application.
     */
    private TeamAssignment loadAssignment(Long id) {
        // Optional is Java's "maybe there is a value" box. orElseThrow opens it, and throws when
        // it is empty. The lambda is only executed in that empty case, so the message string is
        // not built on the normal path.
        return teamAssignmentRepository.findActiveById(id)
                // French message: "assignment not found: <id>".
                .orElseThrow(() -> new NotFoundException("Affectation introuvable : " + id));
    }

    /**
     * Loads a project that has not been soft-deleted, or fails with 404. Used both as an
     * existence check (in findByProject) and to get the entity the new row is attached to (in
     * assign).
     *
     * <p>Again {@code findActiveById} and not {@code findById}: without the {@code deleted =
     * false} filter, developers could be staffed on a project that has been deleted and that no
     * longer appears anywhere in the interface.
     */
    private Project loadProject(Long id) {
        // French message: "project not found: <id>".
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /**
     * Loads a user who is still active, or fails with 404.
     *
     * <p>Written as {@code findById(...).filter(...)}: the row is read by its primary key, and the
     * "is he still active?" test is then done in Java, on the Optional, instead of in SQL.
     *
     * <p>Be ready for this question at the defence: UserRepository DOES declare a
     * {@code findActiveById(Long)} whose JPQL already carries "AND u.deleted = false", exactly
     * like the {@code findActiveById} used above for the assignment and for the project. Calling
     * it here would give the same 404 in one single query, instead of one query plus a test in
     * memory. The two forms behave the same for the caller; this one is simply not written the
     * same way as its two neighbours.
     */
    private User loadUser(Long id) {
        return userRepository.findById(id)
                // filter() turns a user who EXISTS but is soft-deleted into an empty Optional, so
                // both cases fall into the same orElseThrow below. Without this line, a person
                // deactivated when he left the company could still be put on a new project, and
                // he would appear in the team list of that project.
                .filter(u -> !u.isDeleted())
                // French message: "user not found: <id>". Note that a deactivated user gets the
                // same answer as a user who never existed: the API does not reveal which of the
                // two it is.
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}

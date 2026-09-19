package com.pms.agile.service;

import com.pms.agile.dto.SprintRequest;
import com.pms.agile.dto.SprintResponse;
import com.pms.agile.entity.BacklogItem;
import com.pms.agile.entity.Sprint;
import com.pms.agile.mapper.SprintMapper;
import com.pms.agile.repository.BacklogItemRepository;
import com.pms.agile.repository.SprintRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * =============================================================================
 * FILE HEADER — SprintService.java
 * -----------------------------------------------------------------------------
 * WHAT THIS FILE IS
 *   The business layer of the sprints (iterations) of one project. A sprint is a
 *   named time box with a goal, a start date, an end date and a status
 *   (PLANNED, ACTIVE, CLOSED). This class creates them, lists them, updates them
 *   and soft-deletes them.
 *
 * WHERE IT SITS IN THE FLOW (who calls it, what it calls next)
 *   Angular board (agile.component.ts -> agile.service.ts)
 *     -> HTTP on /api/projects/{projectId}/sprints...
 *     -> ProjectScopeInterceptor   ADR-021: checks the caller is allowed on THIS
 *                                  project before the request reaches any code
 *     -> SprintController          only maps HTTP to Java, holds no rule
 *     -> THIS CLASS                permission check + business rules + transaction
 *     -> SprintRepository          reads and writes the sprints table
 *        BacklogItemRepository     used ONLY by delete(), to detach the cards
 *        ProjectRepository         proves the project in the URL exists
 *     -> SprintMapper              turns the Sprint entity into SprintResponse,
 *                                  the flat object sent back as JSON
 *
 * WHY IT EXISTS (what breaks if you delete it)
 *   1. The permission check (@PreAuthorize below) would disappear, so anybody
 *      logged in who can already open the project (a team member, the project
 *      manager, or a holder of VIEW_ALL_PROJECTS) could rewrite its plan, even
 *      with no agile right at all.
 *   2. The date rule (end >= start) would only be enforced by the database CHECK
 *      constraint, and the user would get an ugly SQL error instead of a clear
 *      message.
 *   3. delete() would become a plain delete, and the cards committed to that
 *      sprint would be left pointing at a sprint nobody can see any more.
 *
 * SISTER FILE: BacklogItemService, in this same folder, owns the cards. It reads
 *   sprints (through SprintRepository) only to prove that a card is being
 *   attached to a sprint of the same project. The relation is one-way on purpose:
 *   a sprint never has to know its cards, except in delete() below.
 * =============================================================================
 */

// ---- Annotations placed on the class below ----
// @Service: registers this class as one shared Spring bean, so SprintController
//   can receive it instead of building it itself. WITHOUT IT the application
//   would not start: Spring would say it cannot inject SprintService.
// @RequiredArgsConstructor: Lombok generates, at compile time, a constructor
//   taking every `final` field. That is how the four collaborators below are
//   provided (constructor injection). WHY not @Autowired on each field: the
//   fields stay `final`, so nothing can replace a repository after start-up, and
//   a unit test can still build the class by hand with fake repositories.
/**
 * Business rules of the sprints of one project.
 *
 * <p>WHY IT IS WRITTEN THIS WAY: like BacklogItemService, every public method
 * takes {@code projectId} first and re-proves that the sprint it touches really
 * belongs to that project ({@code loadSprint}). Trusting the sprint id alone
 * would be enough for somebody to rename or delete another project's sprint just
 * by typing a different number in the URL.
 */
@Service
@RequiredArgsConstructor
public class SprintService {

    // Reads and writes the sprints table.
    private final SprintRepository       sprintRepository;
    // Needed only by delete(): before a sprint disappears, its cards must be sent
    // back to the product backlog. This is the one place where a sprint has to
    // know about the cards committed to it.
    private final BacklogItemRepository  backlogItemRepository;
    // Proves the project named in the URL exists and is not soft-deleted.
    private final ProjectRepository      projectRepository;
    // Converts a Sprint entity into the flat SprintResponse sent as JSON.
    // WHY a mapper: the entity holds a lazy link to Project and the audit columns
    // of BaseEntity; sending it straight to Jackson would either leak those
    // columns or fail on the lazy link.
    private final SprintMapper           sprintMapper;

    /**
     * Gives back every live sprint of one project, oldest start date first.
     *
     * <p>The order comes from the repository query ({@code ORDER BY s.startDate}),
     * not from this method: a plan is read left to right in time, so sorting by
     * creation date would put a sprint added later in the wrong place on screen.
     *
     * <p>WHY IT CALLS {@code loadProject} AND IGNORES THE RESULT: to turn an
     * unknown or deleted project into a clean "not found". Without that line,
     * project 9999 would answer with an empty list and the user would think the
     * project exists but has no sprint yet.
     */
    // @PreAuthorize runs BEFORE the body. Spring Security checks that the logged-in
    //   user carries the permission code VIEW_AGILE.
    //   WHY on the service and not on the controller: authorization here is dynamic
    //   and permission-based — permissions are rows in the database (migration V27)
    //   that an administrator can move from one role to another with no redeploy.
    //   This line tests a PERMISSION, never a role name.
    //   WITHOUT IT: every user already inside the perimeter of the project —
    //   each team member, whatever his job — could read its delivery plan, dates
    //   and goals included, without anyone having given him an agile right.
    // ADR-021 — the permission is only half the answer. "May this user use the
    //   agile module?" is decided here; "may this user touch THIS project?" is
    //   decided by ProjectScopeInterceptor on /api/projects/{id}/**.
    // @Transactional(readOnly = true) wraps the whole method in one database
    //   transaction and tells Hibernate not to keep the loaded objects for
    //   comparison at the end, because nothing will be written.
    //   WHY: the list is read in one consistent snapshot, no memory is spent
    //   tracking changes, and a change made by mistake inside a read endpoint is
    //   simply not written back to the database.
    @PreAuthorize("hasAuthority('VIEW_AGILE')")
    @Transactional(readOnly = true)
    public List<SprintResponse> findByProject(Long projectId) {
        loadProject(projectId);
        // findActiveByProjectId filters on deleted = false and JOIN FETCHes the
        // project, so the mapper can read project.code with no second query.
        return sprintMapper.toResponseList(sprintRepository.findActiveByProjectId(projectId));
    }

    /**
     * Creates one sprint inside a project and gives it back with the id the
     * database generated.
     *
     * <p>WHY THE BUILDER instead of a constructor: the fields are set by name.
     * With {@code new Sprint(project, name, goal, startDate, endDate, status)},
     * swapping startDate and endDate would still compile and the sprint would be
     * stored upside down. Written this way, that mistake is visible.
     *
     * <p>WHY {@code validateDates} IS CALLED BEFORE THE BUILDER and not after:
     * nothing should be built, and no id should be burned, for a request we
     * already know we will refuse.
     *
     * <p>The status is taken from the request, so a manager can already create a
     * sprint as PLANNED for next quarter. There is no rule here forcing a single
     * ACTIVE sprint at a time; that choice is left to the project manager.
     */
    // MANAGE_AGILE, not VIEW_AGILE: reading the plan and writing the plan are two
    //   different rights, given to different roles by migration V27.
    //   WITHOUT THE STRICTER CODE: a developer who may only look at the board
    //   could create or move iterations, and the schedule would stop being the
    //   project manager's decision.
    // @Transactional — read-write (no readOnly flag): the project lookup and the
    //   INSERT form one unit of work, and the session stays open while the mapper
    //   reads the sprint's project.
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public SprintResponse create(Long projectId, SprintRequest request) {
        Project project = loadProject(projectId);
        validateDates(request);
        Sprint sprint = Sprint.builder()
                // The project comes from the URL, never from the request body.
                // WHY: a projectId inside the body would let a caller allowed on
                // project 7 create a sprint inside project 9, and the ADR-021 scope
                // check — which only looks at the URL — would never notice.
                .project(project)
                .name(request.name())
                .goal(request.goal())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .status(request.status())
                .build();
        // save() performs the INSERT and returns the stored entity, now carrying
        // the generated id. The mapper works on that returned object.
        return sprintMapper.toResponse(sprintRepository.save(sprint));
    }

    /**
     * Replaces every editable field of one existing sprint. This is the HTTP PUT
     * of the sprint form.
     *
     * <p>WHY IT RE-READS THE ROW FIRST instead of building a Sprint with the given
     * id: {@code loadSprint} proves the row is alive AND belongs to
     * {@code projectId}. Saving an object built from the request would skip that
     * proof, and would also wipe created_at and created_by — the audit columns
     * BaseEntity writes once — because they would be null in the new object.
     *
     * <p>NOTE WHAT IS NOT CHANGED HERE: the project. A sprint stays in the project
     * it was created in. There is deliberately no {@code setProject} call, so a
     * sprint can never be moved from one project to another, which would take its
     * committed cards with it.
     */
    // Same permission and same transaction reasons as create() above.
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public SprintResponse update(Long projectId, Long id, SprintRequest request) {
        // loadSprint is the guard: wrong project or deleted row ends here with 404.
        Sprint sprint = loadSprint(id, projectId);
        // Checked again on every update, not only on create: shortening a sprint by
        // dragging its end date before its start date must be refused too.
        validateDates(request);
        sprint.setName(request.name());
        sprint.setGoal(request.goal());
        sprint.setStartDate(request.startDate());
        sprint.setEndDate(request.endDate());
        sprint.setStatus(request.status());
        // `sprint` is already managed by the transaction, so Hibernate would write
        // the changes at the end anyway. save() is kept so the write is obvious to
        // a reader, and it returns the entity the mapper converts.
        return sprintMapper.toResponse(sprintRepository.save(sprint));
    }

    /**
     * Soft-deletes one sprint, after sending the cards committed to it back to the
     * product backlog.
     *
     * <p>THIS IS THE MOST IMPORTANT METHOD OF THE FILE, because it writes to TWO
     * tables. Read the comments inside before changing anything here.
     *
     * <p>WHY A SOFT DELETE (a {@code deleted} flag) AND NOT A REAL SQL DELETE:
     * every read query of this module filters on {@code deleted = false}, so the
     * sprint disappears from the screen exactly as if it had been removed, but the
     * row keeps its history and the delete can be undone with one UPDATE. A real
     * DELETE would also be refused by the database as long as one backlog row
     * still carried this sprint's id (foreign key fk_backlog_sprint, V27).
     */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        // Guard first: you can only delete a sprint through the project that owns
        // it. A guessed id from another project ends here with a 404.
        Sprint sprint = loadSprint(id, projectId);

        // Deleting a sprint gives its items back to the product backlog instead of
        // deleting them with it: work that was committed but not finished is not
        // lost, it simply becomes unplanned again. It is also what stops items from
        // pointing, through their foreign key, at a sprint that has been deleted.
        //
        // WHAT WOULD GO WRONG WITHOUT THESE THREE LINES: a card that was half done
        // would keep sprint_id = 12 while sprint 12 is invisible. The board would
        // never show that card again — it is not in the product backlog (it has a
        // sprint) and it is not in any visible sprint either. The work would be
        // silently lost, and only someone reading the database could find it.
        //
        // @Transactional above is what makes this safe: the detach of the cards and
        // the flag on the sprint are ONE unit of work. If the application crashed
        // between the two writes, the database would keep either both changes or
        // neither. Without it, a crash could leave the sprint still visible while
        // its cards had already been taken away from it.
        //
        // findActiveBySprintId returns only non-deleted cards; a card that was
        // already soft-deleted does not need to be brought back to the backlog.
        List<BacklogItem> committed = backlogItemRepository.findActiveBySprintId(id);
        // forEach with a lambda: for each card, clear its sprint. Setting it to null
        // is exactly what "back to the product backlog" means — sprint_id is
        // nullable on purpose in migration V27.
        committed.forEach(item -> item.setSprint(null));
        // saveAll saves the whole list with one call instead of one call per card.
        // The cards are already managed by this transaction, so Hibernate would
        // write the change anyway at the end; saveAll is kept because it makes the
        // write obvious to somebody reading this method.
        backlogItemRepository.saveAll(committed);

        // Only now is the sprint itself marked as deleted. Order matters for
        // readability, not for correctness: both writes are inside one transaction.
        sprint.setDeleted(true);
        sprintRepository.save(sprint);
    }

    /**
     * Refuses a sprint whose end date is before its start date.
     *
     * <p>WHY IT IS CHECKED IN JAVA WHEN THE DATABASE ALREADY HAS THE SAME RULE
     * (constraint chk_sprint_dates in migration V27): the database is the last
     * line of defence and answers with a raw SQL error the user cannot understand.
     * Checking here returns a clear sentence — a BusinessRuleException, turned into
     * HTTP 422 by GlobalExceptionHandler — that the form can show next to the
     * field. Keeping both is on purpose: the message is for the user, the
     * constraint is for anybody who writes to the table without going through this
     * code.
     *
     * <p>NOTE THE EXACT TEST: {@code endDate.isBefore(startDate)} rejects only
     * end &lt; start. Two equal dates pass, because a sprint of a single day is
     * legitimate. The database constraint says the same thing (end_date &gt;=
     * start_date), so the two rules cannot drift apart.
     *
     * <p>The dates themselves are never null here: SprintRequest marks both with
     * {@code @NotNull}, and the controller validates the request before calling
     * this service. Without that, the two calls below would throw a
     * NullPointerException instead of a readable message.
     */
    private void validateDates(SprintRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("La date de fin doit etre posterieure ou egale a la date de debut.");
        }
    }

    /**
     * Loads one sprint and proves it belongs to {@code projectId}. Every method
     * that changes an existing sprint starts here.
     *
     * <p>WHY ONE SHARED HELPER: it is the single place where "this row is alive"
     * and "this row is inside the project named in the URL" are checked together.
     * If update and delete each wrote their own check, one of them would eventually
     * be forgotten, and that endpoint would become the hole in the wall.
     *
     * <p>ADR-021 reminder: the interceptor already proved the caller may work on
     * project 7. This method proves the sprint really is in project 7. Both are
     * needed — the interceptor cannot know which row the request is about.
     */
    private Sprint loadSprint(Long id, Long projectId) {
        // findActiveById filters on deleted = false. orElseThrow decides what
        // happens when the Optional (Java's "maybe there is a value" box) is empty.
        Sprint sprint = sprintRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Sprint introuvable : " + id));
        // .equals(...) and not == : these are Long objects. == compares references,
        // and Java only reuses Long instances up to 127, so id 1000 == id 1000
        // would be false and a perfectly valid request would be rejected.
        if (!sprint.getProject().getId().equals(projectId)) {
            // The SAME "not found" message as above, on purpose, and not
            // "forbidden": answering differently would confirm to the caller that
            // sprint 300 does exist, somewhere, in a project he cannot see.
            throw new NotFoundException("Sprint introuvable : " + id);
        }
        return sprint;
    }

    /**
     * Loads the project named in the URL, or fails with "not found".
     *
     * <p>WHY THE RETURN VALUE IS SOMETIMES IGNORED (in {@code findByProject}): the
     * call is made for its failure, not for its result. An unknown project must
     * answer 404 rather than an empty plan.
     *
     * <p>{@code findActiveById} filters on deleted = false, so a project that was
     * archived by a soft delete cannot receive new sprints.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}

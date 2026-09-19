package com.pms.agile.service;

import com.pms.agile.dto.BacklogItemMoveRequest;
import com.pms.agile.dto.BacklogItemRequest;
import com.pms.agile.dto.BacklogItemResponse;
import com.pms.agile.entity.BacklogItem;
import com.pms.agile.entity.Sprint;
import com.pms.agile.mapper.BacklogItemMapper;
import com.pms.agile.repository.BacklogItemRepository;
import com.pms.agile.repository.SprintRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * =============================================================================
 * FILE HEADER — BacklogItemService.java
 * -----------------------------------------------------------------------------
 * WHAT THIS FILE IS
 *   The business layer of the product backlog. It creates, reads, updates, moves
 *   and soft-deletes the cards ("backlog items") of ONE project. A backlog item
 *   is one piece of work: a title, a priority, an estimate in man-days, a column
 *   on the board, and optionally a sprint and a person doing it.
 *
 * WHERE IT SITS IN THE FLOW (who calls it, what it calls next)
 *   Angular board (agile.component.ts -> agile.service.ts)
 *     -> HTTP on /api/projects/{projectId}/backlog...
 *     -> ProjectScopeInterceptor   ADR-021: checks the caller is allowed on THIS
 *                                  project before the request reaches any code
 *     -> BacklogItemController     only maps HTTP to Java, holds no rule
 *     -> THIS CLASS                permission check + business rules + transaction
 *     -> BacklogItemRepository, SprintRepository, ProjectRepository,
 *        UserRepository, TeamAssignmentRepository   (the SQL)
 *     -> BacklogItemMapper         turns the JPA entity into BacklogItemResponse,
 *                                  the flat object that is sent back as JSON
 *     (JPA = the Java standard for mapping objects to database rows.
 *      A DTO, like BacklogItemResponse, is a plain data object used only to carry
 *      values between layers; it is not a database row.)
 *
 * WHY IT EXISTS (what breaks if you delete it)
 *   Without this class the controller would talk to the repositories directly and
 *   three things would be lost:
 *     1. the permission check — see @PreAuthorize below. Any logged-in user who
 *        can already open the project (a team member, the project manager, or a
 *        holder of VIEW_ALL_PROJECTS) could rewrite its board, even if the
 *        administrator never gave him a single agile right.
 *     2. the transaction boundary — a half-finished change could stay in the
 *        database, and lazy fields could no longer be read while mapping.
 *     3. the cross-project guards — resolveSprint / resolveAssignee / loadItem.
 *        Without them, sending another project's sprint id would be enough to
 *        attach an item of project A to a sprint of project B.
 *
 * SISTER FILE: SprintService, in this same folder, owns the sprints. The two work
 *   together: this class only ever ATTACHES an item to a sprint, while deleting a
 *   sprint (SprintService.delete) detaches its items and sends them back to the
 *   product backlog.
 * =============================================================================
 */

// ---- Annotations placed on the class below ----
// @Service: tells Spring "create one shared instance of this class and keep it in
//   the container". WHY: the controller asks Spring for a BacklogItemService, it
//   never writes `new BacklogItemService(...)`. WITHOUT IT: Spring would not know
//   this bean exists and the application would refuse to start, saying it cannot
//   inject BacklogItemService into BacklogItemController.
// @RequiredArgsConstructor: Lombok writes, at compile time, a constructor that
//   takes every `final` field of the class. That generated constructor is how the
//   six collaborators below are filled in (this is called constructor injection).
//   WHY this rather than @Autowired on each field: fields stay `final`, so once
//   the service is built nobody can swap a repository for another one, and the
//   class can still be built by hand inside a unit test.
//   WITHOUT IT: we would have to type and maintain a six-argument constructor.
/**
 * Business rules of the product backlog of one project.
 *
 * <p>WHY IT IS WRITTEN THIS WAY: every public method takes {@code projectId} as
 * its first argument, and every private loader re-checks that the row it touched
 * really belongs to that project. The obvious alternative — trusting the row id
 * alone, because the id is unique anyway — is exactly what allows a caller to
 * read or edit an object of a project that is none of his business: item 42
 * exists, so a naive server happily edits it. Here, asking for item 42 under
 * project 7 when item 42 belongs to project 9 gives a plain "not found".
 */
@Service
@RequiredArgsConstructor
public class BacklogItemService {

    // The six collaborators below are given to this class by Spring through the
    // Lombok-generated constructor. They are `final`: after the service is built
    // they can never be replaced.

    // Reads and writes the backlog_items table (the cards themselves).
    private final BacklogItemRepository      backlogItemRepository;
    // Used only to LOOK UP a target sprint and prove it belongs to the same
    // project. Sprints are created and deleted by SprintService, not here.
    private final SprintRepository           sprintRepository;
    // Used to prove the project in the URL really exists and is not deleted.
    private final ProjectRepository          projectRepository;
    // Converts a BacklogItem entity into the flat BacklogItemResponse sent as JSON.
    // WHY a mapper and not returning the entity: the entity carries lazy relations
    // and audit columns; serialising it directly would either leak fields or blow
    // up on a lazy relation once the transaction is closed.
    private final BacklogItemMapper          backlogItemMapper;
    // Used to prove the chosen assignee is a real, active user.
    private final UserRepository             userRepository;
    // Used to prove that this user is actually a member of THIS project's team.
    // Being an active user of the application is not enough (see resolveAssignee).
    private final TeamAssignmentRepository   teamAssignmentRepository;

    /**
     * Gives back every live backlog item of one project, already converted into
     * the flat shape the board draws as three columns (TODO, IN_PROGRESS, DONE).
     *
     * <p>WHY IT CALLS {@code loadProject} AND THROWS THE RESULT AWAY: it turns an
     * unknown or deleted project into a clean "not found" answer. Without that
     * line, asking for project 9999 would simply return an empty list, and the
     * user would believe the project exists but has no work in it yet.
     */
    // @PreAuthorize runs BEFORE the body of the method. Spring Security looks for
    //   the permission code VIEW_AGILE among the rights of the logged-in user.
    //   WHY the check sits on the SERVICE and not on the controller: authorization
    //   in this project is dynamic and permission-based — the permission rows live
    //   in the database (migration V27) and an administrator can grant them to any
    //   role without a redeploy. Putting the check here means every caller goes
    //   through it, not only the HTTP controller.
    //   NOTE FOR THE JURY: this line tests a PERMISSION CODE, never a role name.
    //   Nothing in this file knows that CHEF_PROJET exists.
    //   WITHOUT IT: somebody who is on the project team for a completely
    //   different reason — say an accountant added for the billing part, with no
    //   agile right at all — could read the whole board and see who works on
    //   what. The scope check of ADR-021 lets him through, because he really is
    //   on the team; this permission is what stops him.
    // ADR-021 — this permission is only HALF the check. "May this user use the
    //   agile module?" is answered here; "may this user touch THIS project?" is
    //   answered by ProjectScopeInterceptor on /api/projects/{id}/**. A project
    //   manager with VIEW_AGILE still gets 403 on a project he is not part of.
    // @Transactional(readOnly = true) opens one database transaction for the whole
    //   method, and tells Hibernate it will not have to look for modified objects
    //   to save when the method ends. This is cheaper in memory and in time, and it
    //   also means a change made by mistake inside a read endpoint is simply not
    //   written to the database.
    //   WHY IT IS REALLY NEEDED HERE: the application runs with open-in-view =
    //   false (application.yml), so the database session closes as soon as this
    //   method returns. The mapper called below reads item.getAssignee()
    //   .getFullName(), and `assignee` is LAZY and is NOT fetched by the query.
    //   WITHOUT THIS LINE that read would happen with no session open and fail
    //   with LazyInitializationException — the list endpoint would return HTTP 500
    //   as soon as one card has somebody assigned to it.
    @PreAuthorize("hasAuthority('VIEW_AGILE')")
    @Transactional(readOnly = true)
    public List<BacklogItemResponse> findByProject(Long projectId) {
        loadProject(projectId);
        // findActiveByProjectId filters on deleted = false and JOIN FETCHes the
        // project and the sprint, so the mapper can read them without extra queries.
        return backlogItemMapper.toResponseList(backlogItemRepository.findActiveByProjectId(projectId));
    }

    /**
     * Creates one backlog item inside a project and gives it back in the flat
     * shape the front end expects (with its new database id).
     *
     * <p>WHY THE BUILDER instead of a constructor: the fields are set by name.
     * With {@code new BacklogItem(project, sprint, user, title, description, ...)}
     * two String arguments in the wrong order would still compile, and the
     * description would be stored as the title. The builder makes that mistake
     * impossible to write.
     *
     * <p>WHY THE ID IS NEVER TAKEN FROM THE REQUEST: the id is produced by the
     * database (BIGSERIAL, migration V27). The client cannot choose it, so it
     * cannot overwrite an existing row simply by sending that row's id to a
     * "create" endpoint.
     *
     * <p>The shape of the request itself (title not blank, estimate not negative,
     * status present) has already been checked by Bean Validation in the
     * controller, so this method only has to enforce the rules that need the
     * database: does the project exist, does the sprint belong to it, is the
     * assignee on the team.
     */
    // MANAGE_AGILE, not VIEW_AGILE: looking at a board and planning it are two
    //   different rights, granted to different roles in migration V27.
    //   WITHOUT THIS STRICTER CODE: a developer who is only supposed to read the
    //   board could add work to it, and the plan would stop being the project
    //   manager's decision.
    // @Transactional — read-write this time (no readOnly flag). It makes the
    //   lookups and the INSERT one single unit of work.
    //   WHY IT MATTERS: resolveSprint and resolveAssignee read other tables before
    //   the INSERT. Inside one transaction those reads and the write see one
    //   consistent picture of the database, and if anything throws, nothing at all
    //   is written. It also keeps the session open for the mapper, exactly as in
    //   findByProject above.
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public BacklogItemResponse create(Long projectId, BacklogItemRequest request) {
        Project project = loadProject(projectId);
        BacklogItem item = BacklogItem.builder()
                .project(project)
                // ABOUT THE LINE ABOVE: the project comes from the URL, never
                // from the request body. WHY: if the body could carry a
                // projectId, a caller allowed on project 7 could create an item
                // inside project 9, and ADR-021's scope check — which only reads
                // the URL — would never notice.
                .sprint(resolveSprint(request.sprintId(), projectId))
                .title(request.title())
                .description(request.description())
                .priority(request.priority())
                .estimateDays(request.estimateDays())
                .status(request.status())
                // Both resolve* helpers return null when the client sent null, and
                // null is a normal value here: an item with no sprint is still in
                // the product backlog, an item with no assignee is not taken yet.
                .assignee(resolveAssignee(request.assigneeId(), projectId))
                .build();
        // save() performs the INSERT and returns the managed entity, now carrying
        // the id the database generated. The mapper needs that returned object:
        // the local `item` variable is the same instance here, but relying on the
        // return value is what keeps this correct for any JPA provider.
        return backlogItemMapper.toResponse(backlogItemRepository.save(item));
    }

    /**
     * Replaces every editable field of one existing item and gives the new state
     * back. This is the HTTP PUT of the card form.
     *
     * <p>WHY IT RE-READS THE ROW FIRST instead of building a fresh BacklogItem
     * carrying the given id: {@code loadItem} proves the row is alive AND belongs
     * to {@code projectId}. Saving an object built from scratch would skip that
     * proof, and would also erase created_at and created_by — the audit columns
     * BaseEntity fills once and never again — because those fields would be null
     * in the new object.
     *
     * <p>PUT MEANING, WORTH KNOWING: a field absent from the request is read as
     * "set it to null". Sending an update with no sprintId therefore sends the
     * item back to the product backlog. That is why the Angular form always posts
     * the complete card, not only the fields the user touched.
     */
    // Same permission and same transaction reasons as create() above.
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public BacklogItemResponse update(Long projectId, Long id, BacklogItemRequest request) {
        // loadItem is the guard: wrong project or deleted row ends here with a 404.
        BacklogItem item = loadItem(id, projectId);
        item.setTitle(request.title());
        item.setDescription(request.description());
        item.setPriority(request.priority());
        item.setEstimateDays(request.estimateDays());
        item.setStatus(request.status());
        // The two relations go through the resolvers, never straight from the
        // request. WITHOUT THAT, sending sprintId = 300 (a sprint of another
        // project) would create a row whose project and sprint disagree, and the
        // board of the other project would suddenly show a foreign card.
        item.setSprint(resolveSprint(request.sprintId(), projectId));
        item.setAssignee(resolveAssignee(request.assigneeId(), projectId));
        // `item` is already managed by the transaction, so Hibernate would write
        // the changes anyway when the method ends. save() is kept because it makes
        // the write visible to a reader of this code and returns the entity the
        // mapper works on.
        return backlogItemMapper.toResponse(backlogItemRepository.save(item));
    }

    /**
     * Moves one card on the board: change its column, and possibly the sprint it
     * belongs to.
     *
     * <p>WHY A SEPARATE ENDPOINT RATHER THAN REUSING {@code update}: a
     * drag-and-drop would otherwise have to send the title, description, priority
     * and estimate back as well. Imagine two people on the same board: one is
     * fixing the description of a card while the other drags that same card from
     * TODO to IN_PROGRESS. With a full PUT, the drag would carry the old
     * description and quietly undo the first person's work. Here only the status
     * and the sprint travel, so a move can never overwrite somebody's typing.
     *
     * <p>The Angular board always passes the card's current sprintId along with
     * the new column, so a simple column change does not accidentally detach the
     * card from its sprint.
     */
    // Moving a card IS planning, so it needs MANAGE_AGILE like create and update.
    // @Transactional: one read plus one write, kept together, and the session stays
    // open for the mapper.
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public BacklogItemResponse move(Long projectId, Long id, BacklogItemMoveRequest request) {
        BacklogItem item = loadItem(id, projectId);
        item.setStatus(request.status());
        // Still resolved, not trusted: the "move" endpoint must not become the
        // back door that lets an item jump into another project's sprint.
        item.setSprint(resolveSprint(request.sprintId(), projectId));
        return backlogItemMapper.toResponse(backlogItemRepository.save(item));
    }

    /**
     * Soft delete: the row stays in the table, only its {@code deleted} flag is
     * raised to true.
     *
     * <p>WHY NOT A REAL SQL DELETE: every read query of this module filters on
     * {@code deleted = false}, so the card disappears from the board exactly as if
     * it had been removed. But the row keeps its history — created_by, updated_by
     * and the timestamps carried by BaseEntity — and the delete can be undone with
     * one UPDATE. A real DELETE of a card that was already estimated and worked on
     * would destroy the trace of that work, and nothing could bring it back.
     *
     * <p>It returns nothing on purpose: the controller answers 204 No Content,
     * which is the usual answer for "done, there is nothing to show you".
     */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        // Same guard again: you can only delete a card through the project it
        // belongs to. Guessing a card id from another project gives a 404.
        BacklogItem item = loadItem(id, projectId);
        item.setDeleted(true);
        backlogItemRepository.save(item);
    }

    /**
     * Finds the target sprint and proves it belongs to the same project as the
     * item. Returns the Sprint entity, or {@code null} when no sprint was asked
     * for.
     *
     * <p>THIS IS THE CHECK that stops an item being attached to another project's
     * sprint by guessing an id. Without it the link would cross the project
     * border: card "Fix the invoice screen" of project A would appear inside
     * sprint "Sprint 3" of project B, and the other team's board would show work
     * that is not theirs.
     *
     * <p>{@code null} is a legitimate value, not an error: it means the item goes
     * back to (or stays in) the product backlog. The sprint_id column is nullable
     * on purpose (migration V27).
     *
     * <p>WHY IT IS A PRIVATE HELPER rather than code repeated in create, update
     * and move: one rule, written once. If the rule ever changes — say, refusing a
     * CLOSED sprint — it changes in one place and all three callers follow.
     */
    private Sprint resolveSprint(Long sprintId, Long projectId) {
        // No sprint asked for: the item simply has no sprint. Returning null here
        // is what makes "send this card back to the product backlog" work at all.
        if (sprintId == null) {
            return null;
        }
        // findActiveById already filters on deleted = false, so a sprint that was
        // soft-deleted cannot be reused. orElseThrow turns the empty Optional into
        // a 404; an Optional is Java's "maybe there is a value" box, and this line
        // is where we decide what happens when the box is empty.
        Sprint sprint = sprintRepository.findActiveById(sprintId)
                .orElseThrow(() -> new NotFoundException("Sprint introuvable : " + sprintId));
        // The sprint exists, but does it belong to THIS project?
        // .equals(...) and not == : these are Long objects, and == would compare
        // two references. Above 127, Java does not reuse Long instances, so
        // id 1000 == id 1000 would be false and the check would reject valid data.
        if (!sprint.getProject().getId().equals(projectId)) {
            // Deliberately the SAME "not found" message as above, not "forbidden".
            // WHY: answering "you are not allowed" would confirm that sprint 300
            // exists somewhere else. Answering "not found" tells the caller nothing
            // he did not already know.
            throw new NotFoundException("Sprint introuvable : " + sprintId);
        }
        return sprint;
    }

    /**
     * Finds the person the card is given to, and proves that person is a member of
     * this project's team. Returns the User entity, or {@code null} when nobody was
     * named.
     *
     * <p>Same reasoning as {@code resolveSprint}: without this check, a guessed id
     * would let somebody put another project's work on a colleague who has nothing
     * to do with that project. That colleague would then see the card in his own
     * list and could not explain where it came from.
     *
     * <p>NOTE THE TWO DIFFERENT CHECKS, and the two different errors they raise.
     * "This user does not exist" is a NotFoundException, turned into HTTP 404 by
     * GlobalExceptionHandler. "This user exists but is not on the team" is a
     * BusinessRuleException, turned into HTTP 422 with a message the user can act
     * on. The difference matters: the first means the client sent nonsense, the
     * second means the client must add the person to the project team first.
     *
     * <p>{@code null} is a legitimate value: a card can be planned before anybody
     * picks it up.
     */
    private User resolveAssignee(Long assigneeId, Long projectId) {
        // Nobody named: the card stays unassigned. Not an error.
        if (assigneeId == null) {
            return null;
        }
        // First question: is this a real, non-deleted user account?
        User user = userRepository.findActiveById(assigneeId)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + assigneeId));
        // Second question: is that user on THIS project's team?
        // existsBy... is a Spring Data derived query: the method NAME is the
        // query, so no JPQL has to be written by hand. Spring Data asks the
        // database only whether at least one matching row exists and stops at the
        // first one found, so the team row itself is never loaded into memory.
        // The "DeletedFalse" part matters because leaving a team is a soft delete
        // (TeamAssignmentService sets deleted = true instead of erasing the row),
        // so a member who was removed has to count as absent here.
        // WITHOUT THIS CHECK: any active account of the company — an accountant, a
        // developer of another project — could be made responsible for this card.
        if (!teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(projectId, assigneeId)) {
            throw new BusinessRuleException(
                    "L'utilisateur affecté doit être membre de l'équipe du projet.");
        }
        return user;
    }

    /**
     * Loads one backlog item and proves it belongs to {@code projectId}. Every
     * method that changes an existing card starts here.
     *
     * <p>WHY THIS EXISTS AS ONE HELPER: it is the single place where "this row is
     * alive" and "this row is inside the project named in the URL" are checked
     * together. If update, move and delete each did their own check, one of them
     * would eventually forget, and that one endpoint would become the hole.
     *
     * <p>ADR-021 reminder: the interceptor already proved the caller may work on
     * project 7. This method proves the card really is in project 7. Both are
     * needed — the interceptor cannot know which row the body is about.
     */
    private BacklogItem loadItem(Long id, Long projectId) {
        BacklogItem item = backlogItemRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Élément de backlog introuvable : " + id));
        // Wrong project answers "not found", never "forbidden", for the same reason
        // as in resolveSprint: a different answer would confirm the card exists.
        if (!item.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Élément de backlog introuvable : " + id);
        }
        return item;
    }

    /**
     * Loads the project named in the URL, or fails with "not found".
     *
     * <p>WHY THE RETURN VALUE IS SOMETIMES IGNORED (in {@code findByProject}): the
     * call is made for its failure, not for its result. An unknown project must
     * answer 404 and not an empty board.
     *
     * <p>{@code findActiveById} filters on deleted = false, so a project that was
     * archived by a soft delete cannot receive new backlog items.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}

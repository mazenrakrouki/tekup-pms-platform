package com.pms.agile.mapper;

import com.pms.agile.dto.BacklogItemResponse;
import com.pms.agile.entity.BacklogItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * WHAT THIS FILE IS
 * The translation table between one backlog item as it is stored in the database
 * (the BacklogItem entity = one row of the table backlog_items) and the flat
 * object the browser receives (BacklogItemResponse).
 * DTO means "Data Transfer Object": a small read-only object whose only job is to
 * carry data out of the application. Here it is a Java record, so it has no link
 * to the database, no lazy loading and no behaviour.
 *
 * WHERE IT SITS IN THE FLOW
 *   BacklogItemController  (/api/projects/{projectId}/backlog)
 *     -> BacklogItemService  (findByProject, create, update, move)
 *          -> BacklogItemRepository returns BacklogItem entities
 *          -> THIS MAPPER turns each entity into a BacklogItemResponse
 *     -> Jackson writes that response as JSON for the Angular board.
 * Nothing uses the mapper in the other direction: building an entity from a
 * request is done by hand in BacklogItemService (see "WHY THERE IS NO toEntity").
 * SprintMapper, next to this file, does the same job for the Sprint entity, and
 * the sprintId / sprintName fields below are the link between the two views.
 *
 * WHY IT EXISTS (what would break if you deleted it)
 * 1. Without it the service would return the entity itself. The entity points to
 *    Project and to User, so the JSON would carry the project budget, the client
 *    name, and the assignee e-mail, password hash and token version to anyone
 *    allowed to open the board.
 * 2. The entity links are LAZY: they are loaded only when they are touched.
 *    Jackson writes the JSON after the service transaction is finished, and
 *    application.yml sets open-in-view to false, so serialising an entity
 *    directly would fail with LazyInitializationException on item.getSprint().
 * 3. The flattening done below (project.id -> projectId, assignee.fullName ->
 *    assigneeName) would otherwise be copied by hand in every service method,
 *    and one field forgotten in one method would be a silent bug.
 *
 * HOW MAPSTRUCT WORKS HERE
 * We only declare this interface; we never write the copying code. MapStruct is
 * an annotation processor: during the Maven build (mapstruct-processor is
 * declared in pom.xml) it writes the real class BacklogItemMapperImpl into
 * target/generated-sources/annotations. The copying is therefore plain Java, not
 * reflection: it costs nothing at run time, and renaming a field in the entity
 * breaks the BUILD instead of silently returning null in front of a user.
 */
// @Mapper tells the MapStruct processor to generate that implementation class.
// componentModel = "spring" adds @Component to the generated class, so Spring
// keeps one instance of it in its container.
// Why: BacklogItemService receives the mapper through its constructor
// (@RequiredArgsConstructor on a final field). Without componentModel = "spring"
// the generated class would be an ordinary class that Spring does not know, and
// the application would refuse to start with
// "No qualifying bean of type BacklogItemMapper".
@Mapper(componentModel = "spring")
public interface BacklogItemMapper {

    /**
     * Turns one stored backlog item into the flat object sent to the browser.
     * Gives back a BacklogItemResponse, or null when item is null, because
     * MapStruct always writes that null guard as the first line of the generated
     * method.
     *
     * WHY THE @Mapping LINES BELOW ARE NEEDED
     * MapStruct already copies every field whose name is the same on both sides:
     * id, title, description, priority, estimateDays and status need no line
     * here. Only the fields that come from a LINKED OBJECT need one, because the
     * entity holds objects (project, sprint, assignee) while the response holds
     * plain values. This is called flattening.
     *
     * projectId / projectCode   (source "project.id", "project.code")
     *   WHAT: reads item.getProject().getId() and item.getProject().getCode().
     *   WHY : the board must know which project the card belongs to, but the
     *         Project row also holds the budget, the margin, the client and the
     *         exchange rate.
     *   WITHOUT IT: we would have to send the whole Project object, so a user who
     *         is only allowed to look at the board would also see the money
     *         figures of the project.
     *
     * sprintId / sprintName   (source "sprint.id", "sprint.name") - sprint can be null
     *   WHAT: reads the same two values through the sprint link. MapStruct writes
     *         a null check for every step of that path (look at the generated
     *         BacklogItemMapperImpl), so a missing sprint gives null, not a crash.
     *   WHY : an item with no sprint is not an error. It is an item still in the
     *         product backlog, not yet committed to an iteration, and
     *         BacklogItemRepository returns it on purpose with a LEFT JOIN.
     *   WITHOUT THAT NULL CHECK: opening the board of a project that holds one
     *         uncommitted item would throw NullPointerException, and the whole
     *         list would answer 500 instead of showing the cards.
     *
     * assigneeId / assigneeName   (source "assignee.id", "assignee.fullName") - can be null
     *   WHAT: assigneeName does NOT come from a column. User.getFullName() builds
     *         it from firstName + " " + lastName, and MapStruct reads that getter,
     *         which is why the property is written "fullName" here.
     *   WHY : a card must show a readable name, not a number, and it must not
     *         carry the rest of the user row.
     *   WITHOUT IT: sending the User object would put email, passwordHash,
     *         tokenVersion and the user role into the board JSON. A null assignee
     *         is normal as well: nobody has picked the item up yet.
     *
     * NOTE ON LOADING: this mapper runs inside the service transaction
     * (the BacklogItemService methods carry @Transactional). project and sprint
     * are already in memory because BacklogItemRepository JOIN FETCHes them;
     * assignee is not fetch-joined, so reading assigneeName makes Hibernate run
     * one extra SELECT for that user. It works because the database session is
     * still open at this point; the same call moved into the controller would
     * fail, since open-in-view is false.
     *
     * WHY THERE IS NO toEntity(BacklogItemRequest)
     * The other direction is written by hand in BacklogItemService, because it is
     * not a copy: resolveSprint() and resolveAssignee() must first check that the
     * sprint id and the user id sent in the body really belong to THIS project.
     * ProjectScopeInterceptor (ADR-021) has already checked the project id in the
     * URL, but it cannot check ids hidden inside the body. A generated mapper
     * that simply set the sprint from sprintId would attach a card to another
     * project iteration as soon as someone guessed an id.
     */
    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "sprintId",     source = "sprint.id")
    @Mapping(target = "sprintName",   source = "sprint.name")
    @Mapping(target = "assigneeId",   source = "assignee.id")
    @Mapping(target = "assigneeName", source = "assignee.fullName")
    BacklogItemResponse toResponse(BacklogItem item);

    /**
     * Turns a whole list of stored items into a list of responses. It is what
     * BacklogItemService.findByProject() uses to answer
     * GET /api/projects/{projectId}/backlog, so it is the call that fills the
     * board with all its cards.
     *
     * WHY DECLARE IT instead of letting each caller write
     * items.stream().map(this::toResponse).toList():
     * MapStruct generates the loop and reuses the single-item rules above, so the
     * mapping of the whole board stays in one place. When a new field is added to
     * BacklogItemResponse there is a single file to change. The generated loop
     * also keeps the order it receives, which matters here because the repository
     * already sorts the rows (ORDER BY b.id) so the cards do not shuffle between
     * two refreshes of the page.
     *
     * The generic type List&lt;BacklogItem&gt; is what tells MapStruct which
     * element mapping to call. Without the type it could not know that each
     * element must go through toResponse(), and the mapping would not compile.
     */
    List<BacklogItemResponse> toResponseList(List<BacklogItem> items);
}

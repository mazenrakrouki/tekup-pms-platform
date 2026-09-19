package com.pms.agile.mapper;

import com.pms.agile.dto.SprintResponse;
import com.pms.agile.entity.Sprint;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * WHAT THIS FILE IS
 * The translation table between one sprint as it is stored in the database (the
 * Sprint entity = one row of the table sprints) and the flat object the browser
 * receives (SprintResponse). A sprint is one iteration of the agile board: a
 * name, a goal, a start date, an end date and a status (PLANNED, ACTIVE,
 * CLOSED).
 * DTO means "Data Transfer Object": a small read-only object that only carries
 * data out of the application. SprintResponse is a Java record, so it has no
 * link to the database and no behaviour.
 *
 * WHERE IT SITS IN THE FLOW
 *   SprintController  (/api/projects/{projectId}/sprints)
 *     -> SprintService  (findByProject, create, update)
 *          -> SprintRepository returns Sprint entities
 *          -> THIS MAPPER turns each entity into a SprintResponse
 *     -> Jackson writes that response as JSON for the Angular board.
 * It is the twin of BacklogItemMapper in the same folder: this one describes the
 * columns of the board (the iterations), the other one describes the cards. The
 * sprintId that BacklogItemMapper puts on each card matches the id produced here,
 * and that is how the front end places a card in the right sprint.
 *
 * WHY IT EXISTS (what would break if you deleted it)
 * 1. Without it the service would return the Sprint entity, which points to the
 *    whole Project row: budget, revised budget, sold margin, client, exchange
 *    rate. All of that would reach any user who is only allowed to read the
 *    board.
 * 2. The project link is LAZY (loaded only when touched) and application.yml
 *    sets open-in-view to false, so Jackson would try to read it after the
 *    transaction is closed and fail with LazyInitializationException.
 * 3. The two flattening rules below would have to be repeated by hand in every
 *    service method that returns a sprint.
 *
 * HOW MAPSTRUCT WORKS HERE
 * We only declare the interface. MapStruct is an annotation processor: during the
 * Maven build it writes the real class SprintMapperImpl into
 * target/generated-sources/annotations. The generated code is plain Java, so it
 * costs nothing at run time, and a field renamed in Sprint or in SprintResponse
 * stops the BUILD instead of returning a null field to the browser.
 */
// @Mapper asks the MapStruct processor to generate that implementation.
// componentModel = "spring" puts @Component on the generated class so Spring
// keeps one instance of it.
// Why: SprintService declares "private final SprintMapper sprintMapper" and
// receives it through its constructor. Without this setting Spring would not
// know the generated class and the application would refuse to start with
// "No qualifying bean of type SprintMapper".
@Mapper(componentModel = "spring")
public interface SprintMapper {

    /**
     * Turns one stored sprint into the flat object sent to the browser. Gives
     * back a SprintResponse, or null when sprint is null, because MapStruct
     * always writes that null guard first.
     *
     * WHY ONLY TWO @Mapping LINES
     * MapStruct copies on its own every field that has the same name on both
     * sides: id, name, goal, startDate, endDate and status. Only the two values
     * that live inside the linked Project object need a rule, because the entity
     * holds an object while the response holds plain values (flattening).
     *
     * projectId   (source "project.id")
     *   WHAT: reads sprint.getProject().getId().
     *   WHY : the front end needs the project key to build its own URLs, for
     *         example to reload /api/projects/{projectId}/backlog after the user
     *         creates a sprint.
     *   WITHOUT IT: the response would either carry the whole Project object, or
     *         no project reference at all, and the board could not tell two
     *         sprints named "Sprint 1" in two different projects apart.
     *
     * projectCode   (source "project.code")
     *   WHAT: reads sprint.getProject().getCode(), the short business key such as
     *         the one printed on the project sheet.
     *   WHY : the screen shows that code to the user, who recognises a project by
     *         its code and not by a database id.
     *   WITHOUT IT: the front end would have to call the projects endpoint again
     *         for every sprint just to display one label, which is one extra HTTP
     *         call per row.
     *
     * Note: sprint.project is declared nullable = false in the entity and is
     * always JOIN FETCHed by SprintRepository, so the project is in memory when
     * this runs. MapStruct still writes null checks along the path, which is the
     * safe behaviour if a sprint is ever built in a test without a project.
     *
     * WHY THERE IS NO toEntity(SprintRequest)
     * SprintService builds the Sprint by hand with the entity builder, because
     * creating a sprint is more than copying: validateDates() refuses an end date
     * before the start date, and the project is taken from the URL (already
     * checked by ProjectScopeInterceptor, ADR-021) and never from the body. A
     * generated reverse mapper would skip both rules.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    SprintResponse toResponse(Sprint sprint);

    /**
     * Turns a whole list of sprints into a list of responses. SprintService
     * .findByProject() uses it to answer GET /api/projects/{projectId}/sprints,
     * which is the call that draws the columns of the board.
     *
     * WHY DECLARE IT rather than writing
     * sprints.stream().map(this::toResponse).toList() in the service: MapStruct
     * generates the loop, it reuses the rules of toResponse() above, and the two
     * flattening rules stay written in one single place.
     * The generated loop keeps the order it is given, and SprintRepository sorts
     * by startDate, so the iterations stay in chronological order on screen. If
     * the service built the list itself with an unordered collection, the columns
     * of the board could appear in a different order at each refresh.
     *
     * The generic type List&lt;Sprint&gt; is what lets MapStruct see that each
     * element must go through toResponse(). Without it the code would not
     * compile, because the processor could not guess the element mapping.
     */
    List<SprintResponse> toResponseList(List<Sprint> sprints);
}

package com.pms.mission.repository;

import com.pms.mission.entity.ComposanteMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: composantes_mission. A "composante" is one cost line of a
 * business trip: a per diem (the fixed daily allowance paid to the traveller), a plane
 * ticket, a tax stamp, a transport fee, or a hotel stay. One mission has several of them.
 * The two methods declared here are reads. The writes (save) come free from JpaRepository,
 * and every business rule and permission check lives in ComposanteService above this file.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser (features/missions/missions.component.ts)
 *     -> MissionController  (/api/projects/{projectId}/missions/{missionId}/composantes)
 *     -> ComposanteService  (permission check, "mission belongs to this project" check,
 *                            currency put in upper case)
 *     -> ComposanteMissionRepository   (THIS FILE)
 *     -> Spring Data JPA / Hibernate -> PostgreSQL table composantes_mission
 * The ComposanteMission objects returned here go to ComposanteMapper, which builds the
 * ComposanteResponse record (a DTO: a small flat object made only to be sent as JSON, so the
 * database entity never leaves the server). The browser then adds the amounts together itself
 * to show the trip total; no total is ever stored in a column.
 * Second caller: the demo loaders (shared/config/DemoDataSeeder and EnterpriseDataSeeder) use
 * only the inherited save(), never the two queries below.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * A mission would have no cost lines, so the cost table on the mission screen would stay empty
 * and no component could be edited or removed. The two methods add exactly what the built-in
 * JpaRepository methods cannot do: they skip soft-deleted rows, they load the parent mission
 * in the same query, and they return the lines in a fixed order.
 *
 * RELATION WITH MissionRepository (the other file of this package)
 * It is a parent/child pair. MissionRepository reads the trip itself (who travels, where,
 * from when to when); this file reads what the trip costs. composantes_mission.mission_id
 * points at missions.id and is NOT NULL, so a cost line can never exist on its own.
 * ComposanteService never trusts the missionId of the URL alone: it first calls
 * MissionService.loadMission (which goes through MissionRepository.findActiveById), then
 * compares that mission's project with the {projectId} of the URL. So in practice every read
 * here happens right after a read in the other file.
 *
 * SECURITY - the two protections that are NOT in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_MISSION')") for reading, and
 *    hasAuthority('MANAGE_MISSION') for writing, sit on the SERVICE methods - not on the
 *    controller and not here. The code never tests a role name, only a permission code, so an
 *    administrator can change which role carries which permission at runtime (ADR-001).
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {id} of the URL
 *    /api/projects/{id}/** and answers 403 when that project is outside the caller's
 *    perimeter. Holding the permission is not enough on its own.
 *
 * WHY AN INTERFACE WITH NO CODE IN IT
 * Nothing implements this interface by hand. At start-up Spring Data scans the interfaces that
 * extend JpaRepository and builds the implementation itself: the inherited methods (save,
 * findById, ...) come from a generic class, and each @Query below is turned into a real SQL
 * statement. No @Repository annotation is needed, because extending JpaRepository is already
 * the signal Spring looks for.
 * WITHOUT THIS MECHANISM: the same JDBC plumbing would have to be written and tested by hand
 * for every table of the application.
 *
 * THE TWO GENERIC TYPES
 * JpaRepository<ComposanteMission, Long> tells Spring Data two things: the entity class this
 * repository works with, and the Java type of its primary key. Both are checked by the
 * compiler, which is why findActiveById(Long) can only be given a Long.
 * WITHOUT THEM: save() would take any Object and a typing mistake - passing a Mission where a
 * ComposanteMission is expected - would only be discovered at run time, in production.
 */
public interface ComposanteMissionRepository extends JpaRepository<ComposanteMission, Long> {

    // WHAT: reads every live cost line of ONE mission, in a fixed order, with the parent
    //       mission row loaded in the same SQL statement.
    // WHY : three separate things are packed into this single line.
    //
    //   (a) "c.deleted = false" - a cost line is never really erased. Deleting it only sets the
    //       boolean column "deleted" to true (see BaseEntity, the parent class of
    //       ComposanteMission, and ComposanteService.delete). This is a "soft delete": the row
    //       stays in the table for the audit trail, so every read has to filter it out itself.
    //       WITHOUT IT: a plane ticket the project manager removed would still be listed, and
    //       still be added into the trip total shown on screen, so the trip would look more
    //       expensive than it really is.
    //
    //   (b) "JOIN FETCH c.mission" - ComposanteMission.mission is mapped FetchType.LAZY, which
    //       means Hibernate normally puts an empty placeholder object there and only goes back
    //       to the database the first time somebody reads it. JOIN FETCH says "bring the
    //       mission row in the same statement". It is worth it because the mission is used on
    //       every row: ComposanteMapper copies mission.id into the response, and
    //       ComposanteService.update/delete compare composante.getMission().getId() with the
    //       missionId taken from the URL.
    //       WITHOUT IT: 8 cost lines can mean 1 query plus up to 8 more, one per mission - the
    //       classic "N+1 select" problem. Worse, application.yml sets "open-in-view: false", so
    //       a placeholder can only be filled while the service transaction is still open; code
    //       reading the mission after the service returned would fail with a
    //       LazyInitializationException, which the user sees as a 500 error.
    //       This is a plain JOIN and not a LEFT JOIN on purpose: mission_id is NOT NULL in
    //       migration V10, so every cost line always has a mission and the join can never drop
    //       a row.
    //
    //   (c) "ORDER BY c.typeComposante" - gives the list a stable, repeatable order, so the
    //       cost table looks the same every time the screen is opened. typeComposante is an
    //       enum stored as text (@Enumerated(EnumType.STRING) on the entity, column
    //       type_composante VARCHAR(30) in V10), so the database sorts that text: BILLET,
    //       PERDIEM, SEJOUR, TIMBRE, TRANSPORT. That is alphabetical order, NOT the order the
    //       constants happen to be written in TypeComposante.java.
    //       WITHOUT IT: PostgreSQL is free to return the rows in whatever order is convenient,
    //       so the same trip could show its per diem above its ticket one day and below it the
    //       next. It looks like a bug to the user, and two screenshots become impossible to
    //       compare.
    //
    // ":missionId" is a named parameter matched to the Java argument of the same name. No
    // @Param annotation is needed, because spring-boot-starter-parent compiles with the
    // -parameters flag, which keeps the real argument names inside the .class file.
    //
    // SPEED: migration V10 creates the matching partial index
    // "CREATE INDEX idx_comp_mission ON composantes_mission(mission_id) WHERE deleted = FALSE".
    // "Partial" means only the live rows are indexed, which matches the filter above exactly.
    @Query("SELECT c FROM ComposanteMission c JOIN FETCH c.mission WHERE c.mission.id = :missionId AND c.deleted = false ORDER BY c.typeComposante")
    List<ComposanteMission> findActiveByMissionId(Long missionId);

    // WHAT: reads ONE live cost line by its id, with its mission already loaded. It gives back
    //       an Optional<ComposanteMission>: a box that either holds the row or is empty.
    // WHY Optional: it forces the caller to deal with the "not found" case instead of quietly
    //       working with null. ComposanteService.loadComposante calls .orElseThrow(...) and
    //       turns the empty box into a clean 404 NotFoundException.
    //       WITHOUT IT: the method would return null and the next composante.setMontant(...)
    //       would fail with a NullPointerException, which the user sees as a 500 error instead
    //       of a plain "not found".
    // WHY NOT the built-in findById(id): findById ignores the soft-delete flag and does not
    //       load the mission. Using it would let somebody edit a cost line that the users had
    //       already deleted, and so bring a removed amount back into the total.
    //
    // WHY the JOIN FETCH matters even more here: both callers of this method
    // (ComposanteService.update and ComposanteService.delete) immediately read
    // composante.getMission().getId() and answer 404 when it is not the missionId of the URL.
    // That comparison is the check that stops somebody changing or deleting a cost line of
    // ANOTHER mission simply by guessing an id - for example editing a colleague's per diem
    // through the URL of their own mission. Because the mission is read on every call,
    // fetching it now costs nothing extra and removes a second query.
    @Query("SELECT c FROM ComposanteMission c JOIN FETCH c.mission WHERE c.id = :id AND c.deleted = false")
    Optional<ComposanteMission> findActiveById(Long id);
}

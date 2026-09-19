package com.pms.mission.repository;

import com.pms.mission.entity.Mission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: missions. A mission is one business trip made by one employee
 * for one project: what it is about (objet), where (lieu), and from which date to which date.
 * The three methods declared here are reads. The writes (save) come free from JpaRepository,
 * and every business rule and permission check lives in MissionService above this file.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser (features/missions/missions.component.ts)
 *     -> MissionController  (/api/projects/{projectId}/missions)
 *     -> MissionService     (permission check, own-only rule, date rule, project match)
 *     -> MissionRepository  (THIS FILE)
 *     -> Spring Data JPA / Hibernate -> PostgreSQL table missions
 * The Mission objects returned here go to MissionMapper, which builds the MissionResponse
 * record (a DTO: a small flat object made only to be sent as JSON, so the database entity never
 * leaves the server).
 * Second caller: ComposanteService, indirectly. It calls MissionService.loadMission - a
 * package-private method that sits on findActiveById below - to check that the mission a cost
 * line belongs to really belongs to the project named in the URL.
 * Third caller: the demo loaders (shared/config/DemoDataSeeder and EnterpriseDataSeeder) use
 * only the inherited save(), never the three queries below.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * No mission could be listed, opened, updated or deleted, so the whole mission screen would
 * disappear and its cost lines would become unreachable as well. The three methods add exactly
 * what the built-in JpaRepository methods cannot do: they skip soft-deleted rows, they load the
 * project and the employee in one query, they sort trips by start date, and - the important one
 * - they offer a second, narrower list restricted to one employee.
 *
 * RELATION WITH ComposanteMissionRepository (the other file of this package)
 * It is a parent/child pair. This file reads the trip; the other file reads what the trip costs
 * (per diem, ticket, stamp, transport, stay). composantes_mission.mission_id points at
 * missions.id, so the two are always read one after the other: first the mission (to know which
 * project it belongs to), then its cost lines.
 * Note that the soft delete does NOT cascade: MissionService.delete only flags the mission row.
 * The cost lines keep deleted = false, but nobody can reach them any more, because every cost
 * endpoint goes through findActiveById below and that query already hides the deleted mission.
 *
 * SECURITY - three layers, only one of which is in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_MISSION')") for reading, and
 *    hasAuthority('MANAGE_MISSION') for writing, sit on the SERVICE methods - not on the
 *    controller and not here. The code never tests a role name, only a permission code, so an
 *    administrator can change which role carries which permission at runtime (ADR-001).
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {id} of the URL
 *    /api/projects/{id}/** and answers 403 when that project is outside the caller's perimeter.
 *    Holding the permission is not enough on its own.
 * 3. Row scope, and this one IS in this file: UC-21 says a developer sees only their own
 *    missions, even inside a project they are allowed to open. That rule is what
 *    findActiveByProjectIdAndUserId exists for. It is applied in the SQL query and not by
 *    filtering a list in memory, so the database never hands the server rows the caller is not
 *    allowed to see. See docs/AUTHORIZATION_MATRIX.md section 5.3.2.
 *
 * WHY AN INTERFACE WITH NO CODE IN IT
 * Nothing implements this interface by hand. At start-up Spring Data scans the interfaces that
 * extend JpaRepository and builds the implementation itself: the inherited methods (save,
 * findById, ...) come from a generic class, and each @Query below is turned into a real SQL
 * statement. No @Repository annotation is needed, because extending JpaRepository is already
 * the signal Spring looks for.
 *
 * THE TWO GENERIC TYPES
 * JpaRepository<Mission, Long> tells Spring Data two things: the entity class this repository
 * works with, and the Java type of its primary key. Both are checked by the compiler.
 * WITHOUT THEM: save() would accept any Object, and passing the wrong entity would only be
 * discovered at run time, in production.
 */
public interface MissionRepository extends JpaRepository<Mission, Long> {

    // WHAT: reads every live mission of one project - all employees together - earliest start
    //       date first, with the project row and the employee row loaded in the same SQL query.
    // WHO CALLS IT: MissionService.findByProject, but only when canSeeAllMissions() is true,
    //       that is when the caller holds MANAGE_MISSION (project manager) or VIEW_ALL_PROJECTS
    //       (director). A developer is sent to findActiveByProjectIdAndUserId instead (UC-21).
    // WHY : three things are packed into this one line.
    //
    //   (a) "m.deleted = false" - a mission is never really erased. Deleting it only sets the
    //       boolean column "deleted" to true (see BaseEntity, the parent class of Mission, and
    //       MissionService.delete). This is a "soft delete": the row stays in the table for the
    //       audit trail, so every read has to filter it out itself.
    //       WITHOUT IT: a trip that was cancelled and deleted would still appear in the list,
    //       and its cost lines would still look like money the project owes.
    //
    //   (b) "JOIN FETCH m.project JOIN FETCH m.user" - both links are mapped FetchType.LAZY on
    //       the Mission entity, which means Hibernate normally puts an empty placeholder object
    //       there and goes back to the database the first time somebody reads it. JOIN FETCH
    //       brings both rows in the same statement. They are needed on every single mission,
    //       because MissionMapper copies project.id, project.code, user.id and
    //       user.getFullName() into the response.
    //       WITHOUT IT: 10 missions can mean 1 query plus up to 20 more, one per project and
    //       one per employee - the classic "N+1 select" problem. And because application.yml
    //       sets "open-in-view: false", a placeholder can only be filled while the service
    //       transaction is still open; mapping a Mission after the service returned would fail
    //       with a LazyInitializationException, seen by the user as a 500 error.
    //       These are plain JOINs and not LEFT JOINs on purpose: project_id and user_id are
    //       both NOT NULL in migration V10, so no mission can ever be dropped by the join.
    //
    //   (c) "ORDER BY m.dateDebut" - trips are read as a timeline, from the oldest to the most
    //       recent departure, not in the order the rows happened to be created.
    //       WITHOUT IT: a trip entered late but planned for January would be shown after the
    //       March one, so the list would read backwards and the user could not tell at a glance
    //       what is coming next.
    //
    // ":projectId" is a named parameter matched to the Java argument of the same name. No @Param
    // annotation is needed, because spring-boot-starter-parent compiles with the -parameters
    // flag, which keeps the real argument names inside the .class file.
    //
    // SPEED: migration V10 creates the matching partial index
    // "CREATE INDEX idx_mission_project ON missions(project_id) WHERE deleted = FALSE".
    // "Partial" means only the live rows are indexed, which matches the filter above exactly.
    @Query("SELECT m FROM Mission m JOIN FETCH m.project JOIN FETCH m.user WHERE m.project.id = :projectId AND m.deleted = false ORDER BY m.dateDebut")
    List<Mission> findActiveByProjectId(Long projectId);

    // WHAT: reads ONE live mission by its id, with its project and its employee already loaded.
    //       It gives back an Optional<Mission>: a box that either holds the mission or is empty.
    // WHY Optional: it forces the caller to deal with the "not found" case instead of quietly
    //       working with null. MissionService.loadMission calls .orElseThrow(...) and turns the
    //       empty box into a clean 404 NotFoundException.
    //       WITHOUT IT: the method would return null and the next mission.setObjet(...) would
    //       fail with a NullPointerException, which the user sees as a 500 error instead of a
    //       plain "not found".
    // WHY NOT the built-in findById(id): findById ignores the soft-delete flag and does not load
    //       the project. Using it would let somebody edit a mission that the users had already
    //       deleted, and would break the project check described just below.
    //
    // WHY "JOIN FETCH m.project" matters most on this method: every caller compares the project
    // of the loaded mission with the {projectId} of the URL and answers 404 when they differ.
    //   - MissionService.update and MissionService.delete -> 404 if they differ.
    //   - ComposanteService.findByMission / create / update / delete -> 404 if they differ.
    // That comparison is what stops somebody reaching a mission of ANOTHER project by guessing
    // its id while using a project they are allowed to open. Without it, the ADR-021 scope check
    // done on the URL by ProjectScopeInterceptor could be walked around, because the interceptor
    // only sees the {projectId} of the path and knows nothing about the mission id.
    // So the project is read on every single call, and fetching it here costs nothing extra.
    //
    // NOTE on scope: this method does not filter by employee. The own-only rule of UC-21 is
    // applied on the list path (see the next method); the paths that use this one are either
    // writes, which require MANAGE_MISSION, or the cost-line reads of ComposanteService.
    @Query("SELECT m FROM Mission m JOIN FETCH m.project JOIN FETCH m.user WHERE m.id = :id AND m.deleted = false")
    Optional<Mission> findActiveById(Long id);

    /** "Own only" perimeter (UC-21: a developer sees only their own missions). */
    // WHAT: same list as findActiveByProjectId, but narrowed to the missions of ONE employee
    //       inside ONE project. Same joins, same soft-delete filter, same order by start date;
    //       the only new thing is the extra condition "m.user.id = :userId".
    // WHO CALLS IT: MissionService.findByProject, on the branch where canSeeAllMissions() is
    //       false - that is, a caller who holds VIEW_MISSION but neither MANAGE_MISSION nor
    //       VIEW_ALL_PROJECTS. In practice: a developer.
    // WHY IT IS A SECOND QUERY instead of filtering the big list in Java afterwards: the rows
    //       the caller may not see must never be loaded at all. Reading everything and then
    //       dropping rows in memory would move private data (who travelled where, and later the
    //       amounts attached to it) into the server's memory for no reason, and any count or
    //       page total computed before the filter would be wrong.
    //       WITHOUT IT: a developer opening a project they legitimately belong to would see the
    //       trips of all their colleagues. This is exactly what the audit measured before the
    //       fix - a developer assigned to one project could read 3 missions that were not his.
    //       See docs/AUTHORIZATION_MATRIX.md section 5.3.2.
    // WHY the rule is chosen by capability and never by role name: MissionService.canSeeAllMissions()
    //       asks for MANAGE_MISSION or VIEW_ALL_PROJECTS. The director holds VIEW_MISSION without
    //       MANAGE_MISSION, so testing MANAGE_MISSION alone would have wrongly locked the
    //       director into their own trips; VIEW_ALL_PROJECTS is the director's existing
    //       scope-lifting capability (ADR-021). Testing a role name instead would break as soon
    //       as an administrator creates a new role (ADR-001).
    // SAFETY DETAIL: MissionService.currentUserId() returns -1 when the logged-in account cannot
    //       be found. -1 is never a real primary key, so this query then returns an empty list.
    //       The failure mode is "you see nothing", never "you see everything".
    //
    // SPEED: migration V10 has two separate partial indexes, idx_mission_project on project_id
    // and idx_mission_user on user_id, both restricted to WHERE deleted = FALSE. There is no
    // combined index on the pair, so PostgreSQL uses one of them and checks the other column on
    // the rows it finds. That is fine here, because one project holds tens of missions, not
    // millions.
    @Query("SELECT m FROM Mission m JOIN FETCH m.project JOIN FETCH m.user WHERE m.project.id = :projectId AND m.user.id = :userId AND m.deleted = false ORDER BY m.dateDebut")
    List<Mission> findActiveByProjectIdAndUserId(Long projectId, Long userId);
}

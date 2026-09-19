package com.pms.team.repository;

import com.pms.team.entity.TeamAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: team_assignments. One row of that table says
 * "this user works on this project, with this role in the team, from this date to
 * that date". A repository is the only place in the application that talks to the
 * database for that table. It holds no business rule and no permission check; both
 * live in the service above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> TeamController           (/api/projects/{projectId}/team
 *                                        and /api/users/{userId}/assignments)
 *           -> TeamAssignmentService    (permission, business rules, transaction)
 *           -> TeamAssignmentRepository (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table team_assignments
 * The TeamAssignment rows returned here are handed to TeamAssignmentMapper, which
 * turns each one into a TeamAssignmentResponse record (id, projectId, projectCode,
 * projectName, userId, userFullName, roleInTeam, startDate, endDate) sent to the
 * browser as JSON. The entity object itself never leaves the server.
 *
 * THIS FILE IS CALLED FROM THREE OTHER MODULES, NOT ONLY FROM THE TEAM MODULE
 * existsByProjectIdAndUserIdAndDeletedFalse(...) is used outside this module by:
 *   - agile    : BacklogItemService, before somebody is made responsible for a
 *                backlog card;
 *   - workload : PlanChargeService and ChargeReelleService, before planned or
 *                real hours are recorded for somebody on a project (marker H-8).
 *                Those two add one exception of their own: the chef de projet of
 *                the project passes even without a row here.
 * And findActiveByProjectId(...) has a second caller too: AgileDemoSeeder, which
 * reads the team of a project to know who can receive the demo cards.
 * So this file is not only the "team tab" of a project: it is the single source of
 * truth that answers the question "is this person on this team, right now?" for the
 * rest of the application.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * TeamAssignmentService, BacklogItemService, PlanChargeService and
 * ChargeReelleService would not compile. Concretely: the team tab of a project
 * would disappear, the "my projects" list of a developer
 * (/api/users/{userId}/assignments) would disappear, a backlog card could be given
 * to somebody who does not work on the project, and hours could be recorded for
 * somebody who is not on the team. The four methods below add exactly what the
 * built-in JpaRepository methods cannot do:
 *   1. they hide the rows flagged deleted = true (soft delete),
 *   2. they load the project and the user in the SAME SQL query, because the mapper
 *      reads both right after,
 *   3. they answer an existence question without loading a row into memory.
 *
 * A NOTE ON THE TABLE ITSELF (migration V6__schema_team.sql)
 * team_assignments is the join table between projects and users, but it is not a
 * plain many-to-many link table: it carries its own data (role_in_team, start_date,
 * end_date) and its own id, which is why it is mapped as a real entity with its own
 * repository instead of a simple @ManyToMany collection. Its safety net inside the
 * database is the partial unique index
 *   CREATE UNIQUE INDEX uk_ta_project_user_active
 *     ON team_assignments(project_id, user_id) WHERE deleted = FALSE;
 * "Partial" means only the live rows are covered. That is deliberate: the same
 * person can be removed from a project (deleted = TRUE) and put back later (a new
 * row with deleted = FALSE) without the old row blocking the new one.
 *
 * THIS TABLE ALSO FEEDS THE PROJECT PERIMETER (ADR-021) - but not from here
 * Being a live member of a team is one of the two ways a user gets access to a
 * project (the other is being its chef de projet). That perimeter query is NOT in
 * this file: it is ProjectRepository.findAccessibleProjectIdsByEmail(email), which
 * reads the TeamAssignment rows inside a sub-query (marker M-6: one single SQL
 * instead of three). Said plainly, so the two are not mixed up at the defence: this
 * repository serves the team SCREENS, ProjectRepository serves the SCOPE CHECK.
 *
 * SECURITY - the two protections that are NOT written in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_TEAM')") for reading and
 *    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')") for writing sit on the
 *    SERVICE methods, never on the controller and never here. The code tests a
 *    permission code, never a role name, so an administrator can move a permission
 *    from one role to another while the application is running.
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {projectId} of the
 *    URL /api/projects/{id}/** and answers 403 when that project is outside the
 *    perimeter of the caller. Holding the permission is NOT enough.
 * So a repository method is never safe on its own: calling it from a new place
 * without going through the service would skip both checks.
 */
// No class implements this interface anywhere in the project, and that is normal:
// Spring Data JPA reads the interface at startup and builds the implementation
// itself (a "proxy" object). It writes the SQL from the @Query text, or from the
// method NAME when there is no @Query, and hands that object to the services that
// asked for a TeamAssignmentRepository.
// Why it is done this way: the alternative is to write by hand, for every method,
// an EntityManager, a createQuery call, the parameter binding and the result list.
// The two types between < > tell the proxy what to work on:
//   TeamAssignment = the entity, so the table read is team_assignments,
//   Long           = the type of the @Id field inherited from BaseEntity, so
//                    findById takes a Long.
// Example of what these generics buy: findById(1L) gives back an
// Optional<TeamAssignment> already typed. Without them it would return Object and
// every caller would need a cast, with a ClassCastException waiting at runtime.
// JpaRepository also brings in, for free, save(), findAll(), count(), deleteById()...
// The application uses save() a lot but never deleteById(): removing somebody from a
// team means setting deleted = true, never erasing the row, because the team history
// of a project has to stay readable.
public interface TeamAssignmentRepository extends JpaRepository<TeamAssignment, Long> {

    // WHAT: gives back ONE live assignment by its own id, with its project row and
    //       its user row already loaded. Optional.empty() when the id does not exist
    //       or when the row was soft-deleted.
    // WHY : this is the entry point of TeamAssignmentService.loadAssignment(), used
    //       by update() and by remove(). Three separate needs are packed into this
    //       single line of JPQL.
    //       (JPQL looks like SQL but is written on the Java classes: "TeamAssignment
    //       ta" is the entity name, not the table name. Hibernate translates it into
    //       real SQL.)
    //
    //   (a) "ta.id = :id" - the primary key lookup itself.
    //
    //   (b) "ta.deleted = false" - removing a member never erases the row, it only
    //       sets the boolean column deleted to true (the field comes from
    //       BaseEntity, the parent class of TeamAssignment). This is a "soft
    //       delete": the row stays in the database for the audit trail, so every
    //       read has to filter it out.
    //       WITHOUT IT: a member removed from the project last month could still be
    //       edited through PUT /api/projects/{id}/team/{assignmentId}, and saving
    //       that row would bring back a membership nobody asked for.
    //
    //   (c) "JOIN FETCH ta.project JOIN FETCH ta.user" - both links are mapped
    //       FetchType.LAZY on the entity, so Hibernate would normally leave them as
    //       empty placeholders and run one extra SELECT the first time each one is
    //       read. TeamAssignmentMapper reads project.id, project.code, project.name,
    //       user.id and user.getFullName() to build the response that update()
    //       returns. JOIN FETCH brings those two rows in the same statement.
    //       WITHOUT IT: one read turns into three round trips to the database and,
    //       worse, application.yml sets "open-in-view: false", which means a lazy
    //       link can only be loaded while the service transaction is still open. Any
    //       mapping done after the service returned would fail with
    //       LazyInitializationException - a 500 error on a screen that looked fine.
    //       These are plain JOINs, not LEFT JOINs, and that is correct here:
    //       project_id and user_id are both NOT NULL on the table (migration V6), so
    //       every assignment always has a project and a user, and no row can be
    //       silently dropped by the join.
    //
    // ":id" is a named parameter matched to the Java argument of the same name. No
    // @Param annotation is needed because the build compiles with the -parameters
    // flag, which keeps the real argument names inside the .class file. That flag
    // is not written in our pom.xml: it is inherited from the parent POM
    // spring-boot-starter-parent, which turns it on for every Spring Boot project.
    // WITHOUT IT the compiler would name the argument "arg0", Spring Data could no
    // longer match it to ":id", and the application would refuse to start with
    // "For queries with named parameters you need to provide names for method
    // parameters" - a startup failure, not a runtime surprise.
    // Why a parameter instead of gluing the value into the query text: the value
    // travels to PostgreSQL apart from the query, so it can never be read as SQL.
    // That is what blocks SQL injection.
    //
    // Optional<...> is the return type on purpose: it forces the caller to say what
    // happens when nothing is found. TeamAssignmentService writes
    // .orElseThrow(() -> new NotFoundException(...)), which becomes a clean 404.
    // WITHOUT IT (returning TeamAssignment and null): a forgotten null check would
    // surface as a NullPointerException, that is a 500 "server error" instead of an
    // honest "this assignment does not exist".
    @Query("SELECT ta FROM TeamAssignment ta JOIN FETCH ta.project JOIN FETCH ta.user WHERE ta.id = :id AND ta.deleted = false")
    Optional<TeamAssignment> findActiveById(Long id);

    // WHAT: gives back every live assignment of ONE project - the team of that
    //       project - with the user row and the project row already loaded.
    // WHY : this is what the team tab of a project shows. It is called by
    //       TeamAssignmentService.findByProject(projectId), and by
    //       AgileDemoSeeder.teamMembers(project), which needs the real team of a
    //       project to hand the demo backlog cards to people who are really on it.
    //       The ingredients are the same as above, with one different filter:
    //
    //   (a) "ta.project.id = :projectId" - the list is per project, because the
    //       screen is always opened inside one project. The filter is written on the
    //       property path ta.project.id, which Hibernate resolves to the foreign key
    //       column project_id: it does NOT add another join to the projects table.
    //       WITHOUT IT: the team tab of project 12 would list every assignment of
    //       the whole company, so a project manager would see who works on the
    //       projects of his colleagues.
    //
    //   (b) "ta.deleted = false" - the same soft-delete filter as above.
    //       WITHOUT IT: people who left the project months ago would still be shown
    //       as members, and assign() - which refuses a duplicate on the live rows
    //       only - would look broken to the user, because the screen would show
    //       somebody the server considers absent.
    //
    //   (c) "JOIN FETCH ta.user JOIN FETCH ta.project" - the same reason as above,
    //       but it matters much more here because this method returns a LIST. The
    //       mapper reads the user and the project of every single row.
    //       WITHOUT IT: a team of 20 people means 1 query plus up to 40 more, two
    //       per row - the classic "N+1 select" problem. A screen that should cost
    //       one round trip costs forty-one.
    //
    // SPEED: migration V6 creates the matching partial index
    //   CREATE INDEX idx_ta_project_id ON team_assignments(project_id)
    //     WHERE deleted = FALSE;
    // Partial means only the live rows are indexed, which matches the two filters
    // above exactly, so PostgreSQL jumps straight to the rows of this project
    // instead of reading the whole table.
    @Query("SELECT ta FROM TeamAssignment ta JOIN FETCH ta.user JOIN FETCH ta.project WHERE ta.project.id = :projectId AND ta.deleted = false")
    List<TeamAssignment> findActiveByProjectId(Long projectId);

    // WHAT: the mirror of the method above - every live assignment of ONE user, that
    //       is, all the projects this person currently works on.
    // WHY : it feeds GET /api/users/{userId}/assignments through
    //       TeamAssignmentService.findByUser(userId). Same ingredients, only the
    //       filtered side changes: "ta.user.id = :userId" instead of the project.
    //       It answers a different question from the same table, which is why the
    //       two methods are kept apart instead of one method with two arguments that
    //       may each be null: each one maps to its own index and reads clearly.
    //
    //       "JOIN FETCH ta.user JOIN FETCH ta.project" is needed for the same N+1
    //       and LazyInitializationException reasons, and here the project side is
    //       the useful one: the response carries projectCode and projectName, so the
    //       user reads project names instead of numbers.
    //       WITHOUT the fetch of the project: listing the 8 projects of a developer
    //       would fire 8 extra SELECTs, or blow up with LazyInitializationException
    //       once the transaction is closed (open-in-view: false).
    //
    // SPEED: migration V6 creates the mirror partial index
    //   CREATE INDEX idx_ta_user_id ON team_assignments(user_id) WHERE deleted = FALSE;
    //
    // WORTH KNOWING FOR THE DEFENCE: this is the only method of the file whose URL
    // does not start with /api/projects/{id}. ProjectScopeInterceptor only matches
    // the pattern ^/api/projects/(\d+)(/.*)?$, so the ADR-021 scope check does not
    // run on /api/users/{userId}/assignments. On that endpoint the VIEW_TEAM
    // permission carried by the service is the only gate.
    @Query("SELECT ta FROM TeamAssignment ta JOIN FETCH ta.user JOIN FETCH ta.project WHERE ta.user.id = :userId AND ta.deleted = false")
    List<TeamAssignment> findActiveByUserId(Long userId);

    // WHAT: answers true or false to "is this user already a live member of this
    //       project?". It loads nothing: the database only has to find one matching
    //       row and can stop there.
    // WHY : this is the most reused method of the file. Four call sites, in three
    //       different modules, need that answer.
    //       1. TeamAssignmentService.assign(...) calls it before creating a row, so
    //          the user gets a clear message ("this user is already a member of this
    //          project") instead of a raw database error. Note that here the answer
    //          is used the other way round: true means "refuse", because the row
    //          already exists.
    //       2. BacklogItemService.resolveAssignee(...) calls it before letting
    //          somebody be made responsible for a backlog card. WITHOUT that check,
    //          any active account of the company - an accountant, a developer of
    //          another project - could be put in charge of a card on a project he
    //          does not work on.
    //       3. and 4. PlanChargeService.assertTeamMembership(...) and
    //          ChargeReelleService.assertTeamMembership(...) call it before planned
    //          hours or real hours are recorded for somebody (marker H-8). WITHOUT
    //          it, effort could be booked on a project for a person who never
    //          worked on it, and every workload and EVM figure built on those hours
    //          would be false.
    //       Those two workload services add one exception this method knows nothing
    //       about: they let the chef de projet of the project through even when he
    //       has no row here, because leading a project counts as being on it. That
    //       rule lives in the services, not in this query, so the query stays the
    //       plain factual question "is there a live team row for this pair?".
    //
    // WHY NO @Query HERE: this is a "derived query". Spring Data reads the method
    // NAME and writes the SQL from it, piece by piece:
    //   existsBy        -> only check that at least one row matches, return a boolean
    //   ProjectId       -> there is no field called projectId on the entity, so
    //                      Spring Data walks the path project -> id, which Hibernate
    //                      resolves to the foreign key column project_id (no extra
    //                      join to the projects table)
    //   And UserId      -> the same walk on user -> id, column user_id
    //   And DeletedFalse-> ... AND deleted = false, the soft-delete filter again.
    //                      It is essential: leaving a team is a soft delete, so a
    //                      member who was removed must count as absent here.
    // Writing it as a name rather than as JPQL keeps the intent in one place; the
    // price to pay is that renaming a field breaks this method at startup instead of
    // at compile time, which is why the method name must never be "tidied up".
    // There is no JOIN FETCH here and that is on purpose: nothing is read from the
    // row, so fetching the project and the user would be wasted work.
    //
    // A boolean is returned rather than the row itself so that the row never travels
    // into memory. Example of the difference: on a project with 200 historical team
    // rows, findActiveByProjectId(...).stream().anyMatch(...) would load 200 objects
    // to answer one yes/no question; this stops at the first match in the database.
    //
    // IMPORTANT - THIS CHECK IS NOT THE REAL PROTECTION AGAINST DUPLICATES.
    // Between the moment this method answers "false" and the moment the INSERT is
    // written, another request could insert the same pair. What actually makes a
    // double membership impossible is the partial unique index of migration V6,
    // uk_ta_project_user_active on (project_id, user_id) WHERE deleted = FALSE.
    // So the two work together: the index guarantees correctness, this method
    // guarantees a readable error message in the normal case.
    boolean existsByProjectIdAndUserIdAndDeletedFalse(Long projectId, Long userId);
}

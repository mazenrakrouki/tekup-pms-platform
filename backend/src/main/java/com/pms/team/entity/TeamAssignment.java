package com.pms.team.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/*
 * =========================================================================
 * WHAT THIS FILE IS
 *   The JPA entity of a team assignment: one person placed on one project for
 *   a period of time. "Entity" means that one Java object of this class equals
 *   one row of the table "team_assignments" (created by migration V6; the two
 *   audit columns created_by and updated_by were added later by V19).
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular team screen
 *     -> TeamController           /api/projects/{projectId}/team
 *                                 /api/users/{userId}/assignments
 *     -> TeamAssignmentService    (permission check + business rules)
 *     -> TeamAssignmentRepository (reads and writes the rows)
 *     -> THIS CLASS               (the row held in memory)
 *     -> TeamAssignmentMapper     -> TeamAssignmentResponse -> JSON -> screen.
 *
 * WHY IT EXISTS
 *   A project has many members and a person works on many projects. That is a
 *   many-to-many link, and such a link needs a table of its own. But the link
 *   here is not a simple pair of ids: it also carries the job done on the
 *   project (roleInTeam) and the period worked (startDate, endDate). A plain
 *   @ManyToMany between Project and User would store only the two ids, and
 *   there would be nowhere to put those three columns. That is why the link is
 *   written as a real entity. Delete this file and the application can no
 *   longer answer "who works on this project", "since when", and "on which
 *   project did this person work last year".
 *
 * IT IS ALSO THE BASE OF "WHO SEES WHAT" (ADR-021)
 *   This table is not only screen data, it feeds the perimeter checks:
 *     - ProjectRepository.findAccessibleProjectIdsByEmail() adds every project
 *       where the user has an active row here. ProjectScopeService uses that
 *       set, and ProjectScopeInterceptor calls it for every URL that looks
 *       like /api/projects/{id}/**. So a row of this table is what lets a
 *       developer open his project at all.
 *     - ResourceRepository limits the resources a project manager may see on
 *       the TCC to the members of the projects he leads.
 *     - BacklogItemService refuses an assignee who has no active row here, so
 *       a card cannot be given to somebody outside the team.
 *     - ChargeReelleService and PlanChargeService refuse to record work for a
 *       person who is not an active member of the project.
 *   Concrete consequence: soft-deleting the wrong row here does not only empty
 *   a list on the screen, it takes the project away from that person (403).
 *
 * "roleInTeam" IS NOT A SECURITY ROLE
 *   The text in roleInTeam ("Developpeur", "Tech Lead", "QA"...) is a label
 *   shown to humans. Authorization in this application is dynamic and based on
 *   permissions: TeamAssignmentService carries @PreAuthorize("hasAuthority
 *   ('VIEW_TEAM')") and @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')"), and
 *   no code anywhere reads the value of this column to decide what somebody is
 *   allowed to do. If it were used that way, anybody able to edit a team line
 *   could raise his own rights by simply typing another job title.
 *   The real security role of a person is somewhere else: User.role, which
 *   points at the roles table and carries the permissions (V2, V12, V25). Only
 *   a user who holds MANAGE_USERS can change that, while ASSIGN_DEVELOPER is
 *   enough to write the label below. That is why the two must stay separate.
 *
 * THE LINK IS MAPPED ONLY FROM THIS SIDE
 *   Project has no List<TeamAssignment> and User has none either. To read the
 *   team of a project the code always goes through TeamAssignmentRepository
 *   (findActiveByProjectId / findActiveByUserId), never through
 *   project.getTeam(). Why it is kept that way: a mapped collection on Project
 *   would load every line, including the soft-deleted ones, because Hibernate
 *   does not know about the "deleted" flag, and a cascade placed on such a
 *   collection could erase team rows for real when a project is saved. Asking
 *   the repository keeps the "deleted = false" filter visible in one place.
 *
 * TWO RULES THAT APPLY TO THE WHOLE FILE
 *   1. There is no security code here. The permission is checked on the
 *      methods of TeamAssignmentService with @PreAuthorize, and the project
 *      perimeter is checked by ProjectScopeInterceptor for every URL of the
 *      form /api/projects/{id}/** (ADR-021). An entity object cannot know who
 *      is asking, so it must not try to decide.
 *   2. Nothing is really deleted. BaseEntity carries a boolean "deleted" and
 *      TeamAssignmentService.remove() only sets it to true (soft delete). The
 *      row stays, so the history of who worked on the project stays readable.
 *      Hibernate does not hide those rows by itself, so every query of
 *      TeamAssignmentRepository states the filter itself: the three @Query
 *      methods write "AND ta.deleted = false" inside the JPQL, and the derived
 *      method carries it in its own name
 *      (existsByProjectIdAndUserIdAndDeletedFalse). Using the ready-made
 *      findAll() of Spring Data instead would put people who left the project
 *      back into the team list, and back inside the project perimeter that
 *      this table controls.
 * =========================================================================
 */

/**
 * One line of a project team: this user, on this project, with this job title,
 * from this date to that date (or still on it when endDate is null).
 *
 * Why the class is written this way, annotation by annotation:
 *  - @Entity tells JPA to manage the class. Without it, TeamAssignmentRepository
 *    fails at start up with "Not a managed type", and the JPQL written in other
 *    modules that names "TeamAssignment" (ProjectRepository, ResourceRepository)
 *    no longer resolves.
 *  - @Table(name = "team_assignments") names the real table. The name Hibernate
 *    would build on its own is "team_assignment" (singular); since the
 *    application starts with ddl-auto=validate (ADR-019: Flyway owns the
 *    schema), it would stop immediately with "table not found" instead of
 *    silently working on a wrong table.
 *  - @Getter / @Setter (Lombok) write the accessors at compile time. The
 *    service and the MapStruct mapper use them; Hibernate reads the fields
 *    directly, because the @Id of BaseEntity is placed on a field.
 *  - @NoArgsConstructor is required by JPA: Hibernate first creates an empty
 *    object, then fills it with the values read from the row. Without it,
 *    reading a team row fails at runtime.
 *  - @AllArgsConstructor is there to feed @Builder. As soon as one constructor
 *    annotation is present, Lombok stops adding on its own the all-fields
 *    constructor that the builder needs.
 *  - @Builder is what TeamAssignmentService.assign() and the demo seeders use.
 *    Two fields here have the same type LocalDate: with a positional
 *    constructor, writing new TeamAssignment(p, u, role, endDate, startDate)
 *    still compiles and silently stores a period that runs backwards. Named
 *    steps such as .startDate(...).endDate(...) make that mistake impossible.
 *    One limit to know: the builder only knows the five fields declared below.
 *    The fields inherited from BaseEntity (id, the audit dates, deleted) are
 *    not builder steps, which is why remove() sets the flag with
 *    ta.setDeleted(true) and does not rebuild the object.
 *  - extends BaseEntity brings id, created_at, updated_at, created_by,
 *    updated_by and the deleted flag - the columns every table of the project
 *    carries since the audit migration V19. Repeating them in each entity would
 *    be copy-paste that slowly drifts apart. BaseEntity also carries
 *    @EntityListeners(AuditingEntityListener.class), and JpaConfig switches
 *    auditing on with @EnableJpaAuditing(auditorAwareRef =
 *    "springSecurityAuditorAware"). Result: created_at/created_by and
 *    updated_at/updated_by are filled by Spring on each save, with the email of
 *    the logged-in user, or with the word "system" when there is no logged-in
 *    user (the seeders that run at start up). No code in this module has to
 *    remember to stamp "who touched the team and when", so every team line can
 *    be traced back to the person who added or changed it.
 *
 * Rules and indexes that live in the database, not in this class:
 *  - uk_ta_project_user_active (V6) is a UNIQUE index on (project_id, user_id)
 *    limited by "WHERE deleted = FALSE". It allows only one ACTIVE line per
 *    person per project, while still allowing several old soft-deleted lines,
 *    so somebody who left can be put back on the project later. A normal unique
 *    constraint would block that second assignment forever.
 *    TeamAssignmentService.assign() asks
 *    existsByProjectIdAndUserIdAndDeletedFalse first, to answer a clear message
 *    instead of a raw SQL error; the index is what still holds if two managers
 *    click "add" at the very same moment and both checks pass.
 *  - chk_ta_dates (V6) checks "end_date IS NULL OR end_date >= start_date".
 *    TeamAssignmentService checks the same thing first, to answer a readable
 *    message, but the database keeps the guarantee even for a row written by a
 *    seeder or by hand in psql.
 *  - idx_ta_project_id and idx_ta_user_id (V6) are two ordinary indexes, also
 *    limited by "WHERE deleted = FALSE". They are what makes the two everyday
 *    queries fast: the team of one project, and the projects of one person.
 *    The second one matters far beyond the screen, because
 *    ProjectRepository.findAccessibleProjectIdsByEmail runs on every request
 *    that ProjectScopeInterceptor guards (ADR-021). Without them PostgreSQL has
 *    to look at far more rows than needed to answer those two questions, and
 *    that cost is paid on each request, not once.
 */
@Entity
@Table(name = "team_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamAssignment extends BaseEntity {

    // WHAT: the project the person is placed on. @ManyToOne = many assignment
    //       rows point to one project; @JoinColumn says the link is stored in
    //       the column project_id (foreign key fk_ta_project, V6).
    // WHY FetchType.LAZY: a @ManyToOne is EAGER by default, so Hibernate would
    //       read the whole Project row every time it reads an assignment, even
    //       when nobody looks at it. Listing the 40 assignments of one user
    //       would then fire 40 extra SELECT statements - the "N+1 queries"
    //       problem. LAZY loads it only when it is really used, and the queries
    //       that do need it ask for it in one go with "JOIN FETCH ta.project".
    // WHY nullable = false: an assignment with no project means nothing, and
    //       the perimeter logic leans on this field. TeamAssignmentService
    //       .update() and .remove() compare ta.getProject().getId() with the
    //       projectId taken from the URL and answer "not found" when they
    //       differ; that is how a member of project A cannot be removed through
    //       the URL of project B. With a null project that comparison would
    //       crash. V6 declares the column NOT NULL too, so the database refuses
    //       such a row as well.
    // NOTE: fk_ta_project is declared ON DELETE CASCADE in V6. It almost never
    //       fires, because projects are soft-deleted and not erased; it is the
    //       safety net for a real DELETE run by hand, so no team line survives
    //       pointing at a project id that no longer exists.
    // SPEED: the column is covered by the partial index idx_ta_project_id (V6,
    //       WHERE deleted = FALSE), which is the one used to build the team
    //       list of a project.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // WHAT: the person placed on the project. The link is stored in the column
    //       user_id (foreign key fk_ta_user, V6).
    // WHY FetchType.LAZY: same reason as above. The team screen of one project
    //       does need the name of each member, so findActiveByProjectId() asks
    //       for the users inside the same query with "JOIN FETCH ta.user"; that
    //       is one query for the whole team instead of one query per member.
    // WHY nullable = false: this is the field the access rules read. A user
    //       with an active row here gets the project inside his perimeter
    //       (ProjectRepository.findAccessibleProjectIdsByEmail), and the agile
    //       and workload modules use the same link to decide who may receive a
    //       card or record hours. A row with no user would be a team member who
    //       is nobody, and it would break those queries.
    // NOTE: fk_ta_user is declared in V6 WITHOUT any ON DELETE clause, unlike
    //       fk_ta_project above. Effect: PostgreSQL refuses a real DELETE of a
    //       user row while assignments still point at it. This fits the way the
    //       application works, since users are soft-deleted (deleted = true)
    //       and never erased, so their past assignments stay and the history of
    //       the project keeps its names. With CASCADE here, erasing one user by
    //       hand would silently wipe his whole track record from every project.
    // SPEED: the column is covered by the partial index idx_ta_user_id (V6,
    //       WHERE deleted = FALSE): the index behind "the projects of this
    //       person", the screen /api/users/{userId}/assignments and the
    //       perimeter query of ADR-021.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // WHAT: the job this person does on this project, as free text, for example
    //       "Developpeur", "Tech Lead" or "QA". It is shown as-is in the team
    //       list (TeamAssignmentResponse.roleInTeam).
    // WHY a String and not an enum: the job titles used on a project change
    //       from one customer to the next, and the company must be able to add
    //       one without a new migration and a new deployment. An enum would
    //       also fail on reading as soon as an old row holds a value that was
    //       later removed from the Java code.
    // WHY length = 50 and nullable = false: they mirror VARCHAR(50) NOT NULL in
    //       V6. Because the application starts with ddl-auto=validate, Hibernate
    //       compares this mapping with the real table at start up: a column
    //       changed in a migration but forgotten here stops the boot, instead of
    //       failing later on the first save made by a user.
    // NOTE: TeamAssignmentRequest adds @NotBlank and @Size(max = 50), so an
    //       empty or too long title is refused with a readable 400 answer before
    //       the database is touched; without it, the same input would come back
    //       to the user as a raw SQL error 500.
    // REMINDER: this value is never read by any security check (see the header).
    @Column(name = "role_in_team", nullable = false, length = 50)
    private String roleInTeam;

    // WHAT: the day the person joins the project.
    // WHY nullable = false: the period is the whole point of this table.
    //       Without a start date, "was this person on the project in March?"
    //       has no answer, and the workload screens could not place the effort
    //       on a timeline. V6 declares the column NOT NULL, and
    //       TeamAssignmentRequest adds @NotNull so the client gets a clear 400
    //       instead of a database error.
    // WHY LocalDate and not LocalDateTime: joining a team happens on a day, not
    //       at 14:37. A date-time would also drag time zones into a value that
    //       has none, and the same day could then compare as two different
    //       values depending on the server clock.
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    // WHAT: the day the person leaves the project.
    // WHY there is no nullable = false here: null is a normal and wanted state,
    //       it means "still on the project today". Most active lines have it, so
    //       forcing a value would push the code to invent a fake far-away date
    //       such as 9999-12-31, and every screen would then have to know that
    //       this fake date means "still here".
    // WHY nothing checks the order of the two dates in this class: an entity
    //       cannot answer an HTTP error. TeamAssignmentService compares them in
    //       assign() and update() and raises BusinessRuleException, which the
    //       client reads as a clear message. The constraint chk_ta_dates of V6
    //       keeps the same rule at database level for rows written outside the
    //       service. Without the two of them, a period ending before it starts
    //       could be stored, and every workload computation over that period
    //       would be wrong.
    @Column(name = "end_date")
    private LocalDate endDate;
}

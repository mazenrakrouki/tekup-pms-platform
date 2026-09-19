package com.pms.agile.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/*
 * =========================================================================
 * WHAT THIS FILE IS
 *   The JPA entity of a sprint. "Entity" means: one Java object here equals
 *   one row of the table "sprints" (created by migration V27). A sprint is one
 *   time box of the agile board, with a name, a goal and two dates.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular agile board (features/agile/agile.component.ts)
 *     -> SprintController    /api/projects/{projectId}/sprints
 *     -> SprintService       (permission check + business rules)
 *     -> SprintRepository    (reads and writes the rows)
 *     -> THIS CLASS          (the row held in memory)
 *     -> SprintMapper        -> SprintResponse -> JSON -> back to the board.
 *   BacklogItemService also reads this class: every backlog item may point to
 *   one sprint, and that service checks the sprint belongs to the same project
 *   before accepting the link.
 *
 * WHY IT EXISTS
 *   Without it there is nothing to attach backlog items to. The board could
 *   only show one flat list with no iteration, and the "planification agile
 *   (sprints, backlog)" requirement of the subject would not be covered. It is
 *   also the only object that says when an iteration starts and when it ends.
 *
 * WHY THERE IS NO LIST OF ITEMS HERE
 *   The obvious alternative is a @OneToMany collection of BacklogItem inside
 *   this class. It is deliberately not done. Reasons:
 *     - soft delete: a mapped collection would load the deleted items too,
 *       because nothing in this project hides them automatically, and a closed
 *       sprint would show cards that were thrown away;
 *     - cascade: a collection invites cascade rules, and a cascade delete here
 *       would destroy the work of the sprint instead of sending it back to the
 *       product backlog, which is what SprintService.delete() really does;
 *     - loading: reading one sprint would drag every card behind it.
 *   The link is kept on one side only, on BacklogItem.sprint, and the code
 *   that needs the cards asks for them explicitly with
 *   BacklogItemRepository.findActiveBySprintId(id).
 *
 * TWO RULES THAT APPLY TO THE WHOLE FILE
 *   1. There is no security code here. The permission (VIEW_AGILE for reading,
 *      MANAGE_AGILE for writing) is checked on the methods of SprintService
 *      with @PreAuthorize, and the project perimeter is checked by
 *      ProjectScopeInterceptor for every URL of the form
 *      /api/projects/{id}/** (ADR-021). An entity cannot know who is asking,
 *      so it must not try to decide.
 *   2. Rows are never really deleted. BaseEntity carries a boolean "deleted"
 *      and SprintService.delete() only sets it to true (soft delete). Hibernate
 *      does not hide those rows by itself, so every query of SprintRepository
 *      writes "AND s.deleted = false" by hand. Calling the ready-made findAll()
 *      of Spring Data instead would bring deleted sprints back onto the board.
 * =========================================================================
 */

/**
 * One iteration (time box) of a project.
 *
 * Why the class is written this way, annotation by annotation:
 *  - @Entity tells JPA to manage the class. Without it, a repository declared
 *    on Sprint fails at start up with "Not a managed type".
 *  - @Table(name = "sprints") names the real table. The default name computed
 *    by Hibernate would be "sprint" (singular); since the application starts
 *    with ddl-auto=validate (ADR-019), it would stop immediately with "table
 *    not found" instead of running against a wrong table.
 *  - @Getter / @Setter (Lombok) write the accessors at compile time. The
 *    services and the MapStruct mapper use them. Hibernate itself works on the
 *    fields directly, because the @Id of BaseEntity is placed on a field.
 *  - @NoArgsConstructor is required by JPA: Hibernate creates an empty object
 *    first and then fills it with the values of the row. Without it, reading a
 *    sprint row fails.
 *  - @AllArgsConstructor is there to feed @Builder. As soon as a constructor
 *    annotation is present, Lombok stops adding on its own the all fields
 *    constructor that the builder calls.
 *  - @Builder is what SprintService.create() uses. With six fields, a
 *    positional constructor is risky: swapping startDate and endDate still
 *    compiles and produces a sprint that ends before it starts. Named steps
 *    such as .startDate(...).endDate(...) make that mistake visible.
 *  - extends BaseEntity brings id, created_at, updated_at, created_by,
 *    updated_by and the deleted flag - the columns every table of the project
 *    carries since the audit migration V19. Repeating them in each entity would
 *    be copy paste that slowly drifts apart.
 */
@Entity
@Table(name = "sprints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sprint extends BaseEntity {

    // WHAT: the project this sprint belongs to. @ManyToOne = many sprints point
    //       to one project; @JoinColumn says the link is stored in the column
    //       project_id of the table sprints (foreign key fk_sprint_project, V27).
    // WHY FetchType.LAZY: a @ManyToOne is EAGER by default, so Hibernate would
    //       read the whole Project row every single time it reads a sprint, even
    //       when nobody looks at it. Listing 20 sprints would then fire 20 extra
    //       SELECT statements - the "N+1 queries" problem - and the page would
    //       slow down for nothing. LAZY loads the project only if somebody asks.
    // AND WHEN IT IS NEEDED: SprintMapper reads project.id and project.code, so
    //       SprintRepository brings the project in the same query with
    //       "JOIN FETCH s.project". That is why LAZY costs nothing here, and why
    //       the mapper cannot hit a "no session" error even though open-in-view
    //       is turned off in application.yml.
    // WHY nullable = false: a sprint with no project could be shown on no board
    //       and would sit outside the perimeter check of ADR-021, which reads the
    //       project id from the URL. The column is NOT NULL in V27 as well, so
    //       the database refuses such a row even if the code tried to write it.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // WHAT: the label the team reads on the board, for example "Sprint 4".
    // WHY length = 255 and nullable = false: they mirror VARCHAR(255) NOT NULL
    //       in V27. Hibernate runs with ddl-auto=validate (ADR-019), so it
    //       compares this mapping with the real table when the application
    //       starts: a column renamed in a migration but forgotten here stops the
    //       boot, instead of failing later on the first save of a user.
    // NOTE: SprintRequest adds @NotBlank, so an empty name is refused with a
    //       readable 400 answer before the database is even touched.
    @Column(nullable = false, length = 255)
    private String name;

    // WHAT: the objective of the iteration, in free text.
    // WHY no nullable = false: a team may create the sprint first and write the
    //       goal later; forcing it would block that normal way of working.
    // WHY length = 1000: it matches VARCHAR(1000) in V27. It is a sentence or
    //       two, not a specification; the limit stops one user from pasting a
    //       whole document into a field that is displayed on a small card.
    @Column(length = 1000)
    private String goal;

    // WHAT: first and last day of the time box.
    // WHY LocalDate and not LocalDateTime: a sprint starts on a day, not at
    //       14:32, and LocalDate carries no time zone. With a date-time, the
    //       same sprint could look like it starts on the 3rd in Tunis and on the
    //       2nd for a browser set to another zone.
    // WHY name = "start_date": the Java field is startDate, the column is
    //       start_date. Writing the name explicitly means the mapping does not
    //       depend on a naming strategy that a future version could change.
    // THE ORDER RULE IS CHECKED TWICE: SprintService.validateDates() throws a
    //       BusinessRuleException (a clean 422 answer with a message the user
    //       can read) and the database repeats the rule as CHECK
    //       chk_sprint_dates (end_date >= start_date, V27). Why both: the
    //       service gives the good message, the constraint is the last guard if
    //       a row ever arrives from an SQL script or from a future service that
    //       forgets the rule.
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // WHAT: where the sprint stands - PLANNED, ACTIVE or CLOSED.
    // WHY @Enumerated(EnumType.STRING): it stores the NAME ("ACTIVE"). The JPA
    //       default is ORDINAL, which stores the position 0, 1, 2. The day
    //       somebody inserts a new constant in the middle of SprintStatus, every
    //       row already saved would silently change meaning: all the ACTIVE
    //       sprints would be read as something else, and nobody would notice
    //       because no error is raised. Text also matches the CHECK constraint
    //       chk_sprint_status of V27 and stays readable in a raw SQL query.
    // WHY length = 10: the column is VARCHAR(10) in V27 and the longest name,
    //       "PLANNED", is 7 characters.
    // WHY @Builder.Default: the builder of Lombok ignores the
    //       "= SprintStatus.PLANNED" written below unless this annotation is
    //       present. Without it, a builder call that forgets .status(...) would
    //       push null into a NOT NULL column and the save would fail at the very
    //       last moment, inside the database.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private SprintStatus status = SprintStatus.PLANNED;
}

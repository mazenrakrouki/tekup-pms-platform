package com.pms.agile.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/*
 * =========================================================================
 * WHAT THIS FILE IS
 *   The JPA entity of a backlog item, that is one card of the agile board.
 *   "Entity" means: one Java object here equals one row of the table
 *   "backlog_items" (created by migration V27; the column assignee_id was
 *   added later by V28).
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular agile board (features/agile/agile.component.ts)
 *     -> BacklogItemController   /api/projects/{projectId}/backlog
 *     -> BacklogItemService      (permission check + business rules)
 *     -> BacklogItemRepository   (reads and writes the rows)
 *     -> THIS CLASS              (the row held in memory)
 *     -> BacklogItemMapper       -> BacklogItemResponse -> JSON -> board.
 *
 * WHY IT EXISTS
 *   It is the unit of work of the agile module. Sprint says "when", this class
 *   says "what has to be done, how big it is, where it stands, and who does
 *   it". Delete this file and a sprint becomes an empty box: no product
 *   backlog, no board, and the "planification agile (sprints, backlog)"
 *   requirement of the subject is no longer covered.
 *
 * THREE LINKS, AND ONLY ONE OF THEM IS REQUIRED
 *   project  : always present -> the item belongs to a perimeter.
 *   sprint   : may be null    -> the item is still in the product backlog.
 *   assignee : may be null    -> nobody has taken the item yet.
 *   Holding the project directly, instead of reaching it through the sprint,
 *   is the important choice of this class. A card that leaves its sprint (the
 *   user drags it back to the backlog, or the sprint is deleted) still knows
 *   its project, so it stays in the project list and stays inside the scope
 *   check of ADR-021. Through the sprint only, an item with no sprint would
 *   belong to nothing and would become invisible and unreachable.
 *
 * TWO RULES THAT APPLY TO THE WHOLE FILE
 *   1. There is no security code here. The permission (VIEW_AGILE for reading,
 *      MANAGE_AGILE for writing) is checked on the methods of
 *      BacklogItemService with @PreAuthorize, and the project perimeter is
 *      checked by ProjectScopeInterceptor for every URL of the form
 *      /api/projects/{id}/** (ADR-021). An entity cannot know who is asking,
 *      so it must not try to decide.
 *   2. Nothing is really deleted. BaseEntity carries a boolean "deleted" and
 *      BacklogItemService.delete() only sets it to true (soft delete). The row
 *      stays, so the history of the sprint stays. Hibernate does not hide those
 *      rows by itself, so every query of BacklogItemRepository writes
 *      "AND b.deleted = false" by hand. Using the ready-made findAll() of
 *      Spring Data instead would bring deleted cards back onto the board.
 * =========================================================================
 */

/**
 * One card of the agile board: a piece of work inside a project, optionally
 * committed to a sprint and optionally given to a member of the team.
 *
 * Why the class is written this way, annotation by annotation:
 *  - @Entity tells JPA to manage the class. Without it, BacklogItemRepository
 *    fails at start up with "Not a managed type".
 *  - @Table(name = "backlog_items") names the real table. The default name
 *    computed by Hibernate would be "backlog_item" (singular); since the
 *    application starts with ddl-auto=validate (ADR-019), it would stop
 *    immediately with "table not found" instead of using a wrong table.
 *  - @Getter / @Setter (Lombok) write the accessors at compile time. The
 *    services and the MapStruct mapper use them. Hibernate itself works on the
 *    fields directly, because the @Id of BaseEntity is placed on a field.
 *  - @NoArgsConstructor is required by JPA: Hibernate creates an empty object
 *    first, then fills it with the values read from the row. Without it,
 *    reading a backlog row fails.
 *  - @AllArgsConstructor is there to feed @Builder. As soon as a constructor
 *    annotation is present, Lombok stops adding on its own the all fields
 *    constructor that the builder calls.
 *  - @Builder is what BacklogItemService.create() uses. With eight fields, a
 *    positional constructor is dangerous: swapping the two String arguments
 *    (title and description) still compiles, and the mistake is only seen on
 *    screen by the user. Named steps such as .title(...).description(...) make
 *    that impossible.
 *  - extends BaseEntity brings id, created_at, updated_at, created_by,
 *    updated_by and the deleted flag - the columns every table of the project
 *    carries since the audit migration V19. Repeating them in each entity would
 *    be copy paste that slowly drifts apart.
 */
@Entity
@Table(name = "backlog_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacklogItem extends BaseEntity {

    // WHAT: the project that owns this card. @ManyToOne = many items point to
    //       one project; @JoinColumn says the link is stored in the column
    //       project_id (foreign key fk_backlog_project, V27).
    // WHY FetchType.LAZY: a @ManyToOne is EAGER by default, so Hibernate would
    //       read the whole Project row every time it reads a card, even when
    //       nobody looks at it. A board with 60 cards would fire 60 extra SELECT
    //       statements - the "N+1 queries" problem - for data the board does not
    //       display. LAZY reads it only when it is really used, and the queries
    //       that need it ask for it in one go with "JOIN FETCH b.project".
    // WHY nullable = false: this field is what the whole perimeter logic leans
    //       on. BacklogItemService.loadItem() compares item.getProject().getId()
    //       with the projectId taken from the URL and refuses the item when they
    //       differ - that is how a card of project A cannot be edited through the
    //       URL of project B. With no project, that comparison would crash on a
    //       null value and the card would escape the scope of ADR-021. V27
    //       declares the column NOT NULL too, so the database refuses it as well.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // WHAT: the iteration the item is committed to, if any.
    // WHY there is no nullable = false: null is a normal state that the product
    //       owner wants, not a missing value - V27 leaves sprint_id nullable for
    //       exactly that reason. Dragging a card out of a sprint sets it back to
    //       null, and SprintService.delete() sets it to null on every item of the
    //       sprint it deletes, so work that was committed goes back to the product
    //       backlog instead of disappearing with the sprint.
    // WHY the value coming from the client is not trusted: BacklogItemService
    //       .resolveSprint() loads the sprint again and refuses it when it
    //       belongs to another project. Without that check, somebody who simply
    //       guesses a number could attach a card to the iteration of another
    //       customer project.
    // CONSEQUENCE ON THE QUERIES: because the link can be null, the read queries
    //       of BacklogItemRepository use LEFT JOIN FETCH. A plain JOIN would keep
    //       only the items that have a sprint, and the whole product backlog
    //       would look empty on the screen.
    /** Null means the item is still in the product backlog, not committed to a sprint. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sprint_id")
    private Sprint sprint;

    // WHAT: the person who does the work. The link is the column assignee_id
    //       (foreign key fk_backlog_assignee, added by V28).
    // WHY the value coming from the client is not trusted: BacklogItemService
    //       .resolveAssignee() loads the user and then checks, through
    //       TeamAssignmentRepository, that this user really belongs to the team
    //       of the project. Without it, a guessed id would give the work of one
    //       project to somebody who does not work on it, and that person would
    //       suddenly see a project they have nothing to do with in their own
    //       task list.
    // NOTE ON LOADING: the list query does not bring the assignee with
    //       JOIN FETCH, while BacklogItemMapper reads assignee.fullName. So
    //       Hibernate loads each assignee with a separate SELECT. It works,
    //       because the mapping is done inside the @Transactional method of the
    //       service (the session is still open), but it is one extra query per
    //       different assignee of the board.
    /**
     * Team member doing the work. Null is legitimate: an item can be committed to
     * a sprint before anyone picks it up, and a product backlog item usually has
     * no owner at all.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    // WHAT: the short sentence written on the card, for example "Export PDF of
    //       the internal quote".
    // WHY length = 255 and nullable = false: they mirror VARCHAR(255) NOT NULL in
    //       V27. Hibernate runs with ddl-auto=validate (ADR-019), so it compares
    //       this mapping with the real table when the application starts: a
    //       column renamed in a migration but forgotten here stops the boot,
    //       instead of failing later on the first save of a user.
    // NOTE: BacklogItemRequest adds @NotBlank, so a card with an empty title is
    //       refused with a readable 400 answer before the database is touched.
    @Column(nullable = false, length = 255)
    private String title;

    // WHAT: the long text of the card (context, acceptance criteria...).
    // WHY no nullable = false: a team writes many cards with a title only, and
    //       the description arrives later during refinement.
    // WHY length = 2000: it matches VARCHAR(2000) in V27, which is the real
    //       limit. The column is a short specification, not a document; a bound
    //       keeps the row small and the board fast to load.
    @Column(length = 2000)
    private String description;

    // WHAT: how urgent the card is - LOW, MEDIUM, HIGH or CRITICAL.
    // WHY @Enumerated(EnumType.STRING): it stores the NAME ("HIGH"). The JPA
    //       default is ORDINAL, which stores the position 0, 1, 2, 3. The day
    //       somebody inserts a new level in the middle of BacklogPriority, every
    //       row already saved would silently change meaning - the cards saved as
    //       HIGH would be read as something else - and no error would warn us.
    //       Text also matches the CHECK constraint chk_backlog_priority of V27
    //       and stays readable in a raw SQL query.
    // WHY length = 10: the column is VARCHAR(10) in V27 and the longest name,
    //       "CRITICAL", is 8 characters.
    // WHY @Builder.Default: the builder of Lombok ignores the "= MEDIUM" written
    //       below unless this annotation is present. Without it, a builder call
    //       that forgets .priority(...) would push null into a NOT NULL column
    //       and the save would fail at the very last moment, inside the database.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private BacklogPriority priority = BacklogPriority.MEDIUM;

    // WHAT: the size of the card, counted in man-days, the same unit as
    //       planned_days in the workload module (PlanCharge). Using one unit for
    //       the two modules is what allows comparing a sprint with the workload
    //       plan of the project.
    // WHY BigDecimal and not double: a double cannot hold 0.1 exactly. Adding
    //       the estimates of a sprint with doubles prints lines such as
    //       7.000000000000001 days on the screen, and a jury would see it.
    //       BigDecimal counts in exact decimal digits: 0.5 + 0.25 gives 0.75.
    // WHY precision = 6, scale = 2: it maps NUMERIC(6,2) of V27 - six digits in
    //       total, two of them after the point, so from 0.00 to 9999.99. Half a
    //       day (0.5) can be written, and a value typed by mistake such as
    //       123456 days is refused instead of being silently rounded.
    // WHY no nullable = false: a card can be written before anyone estimates it.
    //       null means "not estimated yet", which is not the same as 0 ("no
    //       effort"). A negative value is refused twice: @PositiveOrZero in
    //       BacklogItemRequest, and CHECK chk_backlog_estimate in V27.
    /** Effort in man-days, consistent with the workload module. */
    @Column(name = "estimate_days", precision = 6, scale = 2)
    private BigDecimal estimateDays;

    // WHAT: the column of the board where the card sits - TODO, IN_PROGRESS or
    //       DONE. This is the field the drag and drop of the board changes,
    //       through BacklogItemService.move().
    // WHY @Enumerated(EnumType.STRING): same reason as for the priority above -
    //       the name is stored, not the position, so the meaning of the saved
    //       rows never depends on the order of the constants in the enum. It also
    //       matches the CHECK constraint chk_backlog_status of V27.
    // WHY length = 15: the column is VARCHAR(15) in V27 and the longest name,
    //       "IN_PROGRESS", is 11 characters.
    // WHY @Builder.Default: without it the builder would ignore "= TODO" and a
    //       card created without .status(...) would arrive with null in a NOT
    //       NULL column. With it, a new card starts in the first column of the
    //       board, which is what a user expects.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private BacklogItemStatus status = BacklogItemStatus.TODO;
}

package com.pms.mission.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Mission = one business trip made by one person for one project: what the trip is for
 * (objet), where (lieu) and between which two dates. It is one row of the table "missions",
 * created by migration V10.
 *
 * Where it sits in the flow:
 *   MissionController (/api/projects/{projectId}/missions)
 *     -> MissionService (carries the permission check and the transaction)
 *       -> MissionRepository (findActiveByProjectId / findActiveById /
 *          findActiveByProjectIdAndUserId)
 *         -> this entity
 *           -> MissionMapper -> MissionResponse (the JSON sent to the Angular app).
 * Downwards, a Mission is the parent of its cost lines: ComposanteMission points back here
 * through its "mission" field, and ComposanteService always loads the Mission first to
 * check that the trip really belongs to the project in the URL.
 *
 * Why it exists: it is the anchor that ties travel spending to a project and to a person.
 * Delete it and the cost lines of ComposanteMission would float free - amounts belonging to
 * no project, so nobody could say which project paid for them, and the "other costs" stream
 * (missions and frais, kept separate from the labour cost Cout Actuel = JH x TCC, see
 * docs/ARCHITECTURE.md 7.2) would lose its source.
 *
 * Two safety points a jury may ask about:
 * - Scope (ADR-021): every URL of this module starts with /api/projects/{id}/..., so
 *   ProjectScopeInterceptor checks that the caller may see THAT project before the service
 *   runs. The permission VIEW_MISSION alone is not enough; the project must also be inside
 *   the caller's perimeter.
 * - Rows are never really removed. Mission inherits the "deleted" flag from BaseEntity, and
 *   the repository only reads rows where deleted = false (soft delete). A cancelled trip
 *   therefore stays in the database for audit, but disappears from every screen.
 */
/*
 * @Entity tells Hibernate that this class is stored in a table; without it the application
 * would not start, because MissionRepository would report Mission as "not a managed type".
 * @Table fixes the table name to "missions". Without it Hibernate would look for a table
 * named "mission" (the class name) and start-up validation would fail, since the SQL in V10
 * creates "missions" with an s.
 * The Lombok annotations generate code so the file stays readable:
 *   @Getter/@Setter  - the accessors used by the mapper and by MissionService;
 *   @NoArgsConstructor - Hibernate needs an empty constructor to create the object before
 *                        filling the fields it read from the database;
 *   @AllArgsConstructor + @Builder - let MissionService write Mission.builder().objet(...)
 *                        .build() instead of a constructor call with six unnamed arguments,
 *                        where two dates of the same type are easy to swap by mistake.
 */
@Entity
@Table(name = "missions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mission extends BaseEntity {

    /*
     * The project that pays for this trip. @ManyToOne: many missions point to one project.
     *
     * fetch = LAZY means the Project row is not read together with the mission; Hibernate
     * puts a stand-in object in this field and only queries the project if someone calls
     * getProject(). Why: reading a list of 50 missions would otherwise fire 50 extra SELECTs
     * on projects (the "N+1 queries" problem) and the list screen would slow down. When the
     * project is genuinely needed - MissionMapper reads project.id and project.code - the
     * repository asks for it in the same SQL with JOIN FETCH. This matters here because
     * open-in-view is false in application.yml: the Hibernate session is already closed when
     * the controller serialises the answer, so a lazy field touched too late throws
     * LazyInitializationException instead of loading quietly.
     *
     * @JoinColumn: the foreign key column is project_id and nullable = false, so a mission
     * can never be saved without a project. Without that, a trip could exist attached to
     * nothing, and the scope check of ADR-021 - which works by project id - would have
     * nothing to check.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /*
     * The person who travels. Same LAZY reasoning as above; MissionMapper reads user.id and
     * the full name inside the transaction, and the repository queries use JOIN FETCH m.user.
     *
     * This column is not only descriptive, it is a security input: MissionService compares
     * it with the id of the logged-in user. A developer who holds VIEW_MISSION but neither
     * MANAGE_MISSION nor VIEW_ALL_PROJECTS only receives the missions whose user_id is his
     * own (UC-21). Without user_id being mandatory, such a mission would match nobody and
     * would silently vanish from that developer's list.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /*
     * What the trip is for, in the user's own words ("kick-off meeting at the client site").
     * nullable = false: a trip without a reason cannot be justified to the client or to an
     * auditor, so the row is refused. length = 500 matches VARCHAR(500) in V10 - and because
     * application.yml sets ddl-auto to "validate", a mismatch between this number and the
     * real column stops the application at start-up instead of failing later on a long text.
     */
    @Column(nullable = false, length = 500)
    private String objet;

    /*
     * Where the trip takes place. Optional on purpose: no nullable = false, because some
     * missions are local or the place is not known yet when the trip is planned. Code that
     * reads this field must accept null.
     */
    @Column(length = 255)
    private String lieu;

    /*
     * First day of the trip. @Column(name = "date_debut") writes the SQL column name by hand.
     * Spring Boot's default naming strategy would already turn dateDebut into date_debut, so
     * this is not strictly required; it is written down so the mapping stays correct even if
     * that strategy is ever changed, and so the reader sees the real column name at once.
     * LocalDate, not LocalDateTime: a mission is counted in whole days (per diems are per
     * day), so storing an hour would invite time-zone bugs where a trip starting at 00:00 in
     * Tunis appears to start the day before somewhere else.
     */
    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    /*
     * Last day of the trip, inclusive. Both dates are mandatory because the trip length is
     * what per diems are computed from.
     * The rule "end is not before start" is enforced twice: MissionService.validateDates
     * throws a BusinessRuleException so the user sees a clear message, and the database rule
     * chk_mission_dates CHECK (date_fin >= date_debut) from V10 is the last line of defence.
     * Without that second check, a row inserted by a script or by a future service that
     * forgets the validation could hold 10/01 -> 05/01, and any duration computed from it
     * would come out negative.
     */
    @Column(name = "date_fin", nullable = false)
    private LocalDate dateFin;
}

package com.pms.mission.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * One business trip made by one person for one project (objet, lieu, start/end date) — a row
 * of table "missions" (migration V10). Parent of its cost lines (ComposanteMission), which
 * always load the Mission first to confirm it belongs to the project in the URL.
 *
 * Scope (ADR-021): every URL of this module starts with /api/projects/{id}/..., so
 * ProjectScopeInterceptor checks the caller's perimeter before the service runs — the
 * permission VIEW_MISSION alone is not enough. Soft-deleted via the inherited "deleted" flag.
 */
/*
 * @Entity/@Table("missions") are required for Hibernate to manage this class and match the
 * real table name (it would otherwise guess "mission" and fail startup validation).
 * Lombok generates accessors, the no-args constructor Hibernate needs, and a named-argument
 * builder so the two LocalDate fields can't be swapped by position.
 */
@Entity
@Table(name = "missions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mission extends BaseEntity {

    // Project paying for the trip. fetch = LAZY avoids an N+1 SELECT per row when listing
    // missions; the repository JOIN FETCHes it when needed (open-in-view is false, so a late
    // touch throws LazyInitializationException). nullable = false so ADR-021's scope check
    // always has a project to check against.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Traveller. Same LAZY reasoning as project; repository queries use JOIN FETCH m.user.
    // Also a security input: MissionService compares this with the logged-in user's id to
    // restrict a VIEW_MISSION-only holder to their own missions (UC-21).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Purpose of the trip. nullable = false: an unjustified trip is refused. length = 500
    // matches VARCHAR(500) in V10 (checked at startup via ddl-auto: validate).
    @Column(nullable = false, length = 500)
    private String objet;

    // Where the trip takes place. Optional: some missions are local or the place is unknown yet.
    @Column(length = 255)
    private String lieu;

    // First day of the trip. LocalDate, not LocalDateTime: a mission is counted in whole days
    // (per diems are per day), avoiding timezone-shift bugs.
    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    // Last day of the trip, inclusive. "End not before start" is enforced twice:
    // MissionService.validateDates (clear error message) and DB rule chk_mission_dates (V10,
    // last line of defence).
    @Column(name = "date_fin", nullable = false)
    private LocalDate dateFin;
}

package com.pms.team.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * JPA entity for one team_assignments row: a person on a project, with a role label and a
 * period. A real entity rather than a plain @ManyToMany because the link itself carries data
 * (roleInTeam, startDate, endDate). Also the basis of the ADR-021 perimeter checks: an active
 * row here is what puts a project inside a user's accessible set (and gates card assignment
 * and workload recording elsewhere), so soft-deleting the wrong row takes the project away
 * from that person (403), not just from a screen list.
 *
 * <p>roleInTeam is a display label only, not a security role — authorization is
 * permission-based (@PreAuthorize on TeamAssignmentService), never read from this column.
 *
 * <p>Mapped only from this side (no Project.teamAssignments / User.teamAssignments):
 * TeamAssignmentRepository's queries keep the "deleted = false" filter visible in one place,
 * since a mapped collection would ignore soft-delete and risk a cascade erasing rows for real.
 */
@Entity
@Table(name = "team_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamAssignment extends BaseEntity {

    // LAZY to avoid N+1 selects; queries that need it use JOIN FETCH ta.project. nullable=false
    // because update()/remove() rely on comparing this project's id to the URL's projectId.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // LAZY, loaded via JOIN FETCH ta.user where needed. This is the field the perimeter and
    // workload/agile access checks read, so it must never be null.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Free text, not an enum: job titles vary per customer and must not require a migration to add.
    @Column(name = "role_in_team", nullable = false, length = 50)
    private String roleInTeam;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    // Null means still on the project — deliberately not forced to a far-future sentinel date.
    // End-after-start is enforced in TeamAssignmentService (422) and by chk_ta_dates in the DB,
    // not here: an entity can't raise an HTTP error.
    @Column(name = "end_date")
    private LocalDate endDate;
}

package com.pms.agile.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

// JPA entity for one row of the "sprints" table (migration V27): one time box of the agile
// board, with a name, a goal and two dates. No @OneToMany list of items here on purpose — it
// would load soft-deleted cards, invite an accidental cascade delete of a sprint's work instead
// of sending it back to the product backlog, and drag every card in just to read one sprint.
// The link lives only on BacklogItem.sprint; callers fetch cards explicitly via
// BacklogItemRepository.findActiveBySprintId. No security code here either: permissions are
// checked in SprintService, and every repository query must add "AND s.deleted = false" by hand.

/**
 * One iteration (time box) of a project.
 */
@Entity
@Table(name = "sprints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sprint extends BaseEntity {

    // LAZY to avoid an N+1 SELECT per sprint when listing; SprintMapper needs project.id/code
    // so SprintRepository brings it in with "JOIN FETCH s.project" instead. NOT NULL: a sprint
    // with no project would sit outside the ADR-021 scope check.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 255)
    private String name;

    // No nullable=false: a team may create the sprint first and write the goal later.
    @Column(length = 1000)
    private String goal;

    // LocalDate, not LocalDateTime: a sprint starts on a day, and a time zone would risk
    // showing a different date to a browser in another zone.
    // "end >= start" is checked twice: SprintService.validateDates (clean 422) and DB CHECK
    // chk_sprint_dates (last guard against a row written outside the service).
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // EnumType.STRING (not the JPA default ORDINAL) so inserting a new status later can't
    // silently reinterpret rows already saved.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private SprintStatus status = SprintStatus.PLANNED;
}

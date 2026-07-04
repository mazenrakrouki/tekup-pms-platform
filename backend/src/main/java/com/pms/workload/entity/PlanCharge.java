package com.pms.workload.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "plan_charges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanCharge extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDate period;

    @Column(name = "planned_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal plannedDays;
}

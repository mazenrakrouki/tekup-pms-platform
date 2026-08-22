package com.pms.agile.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Itération de travail rattachée à un projet.
 *
 * Le sprint ne porte aucune donnée financière : il référence {@code project_id} et rien
 * d'autre. Le moteur KPI, la facturation et le devis interne ne le consultent pas.
 */
@Entity
@Table(name = "sprints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sprint extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 1000)
    private String goal;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private SprintStatus status = SprintStatus.PLANNED;
}

package com.pms.kpi.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "snapshot_kpis")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SnapshotKpi extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "budget_planifie", precision = 15, scale = 2)
    private BigDecimal budgetPlanifie;

    @Column(name = "budget_consome", precision = 15, scale = 2)
    private BigDecimal budgetConsome;

    @Column(name = "eac", precision = 15, scale = 2)
    private BigDecimal eac;

    @Column(name = "marge", precision = 15, scale = 2)
    private BigDecimal marge;

    @Column(name = "taux_consommation", precision = 7, scale = 4)
    private BigDecimal tauxConsommation;
}

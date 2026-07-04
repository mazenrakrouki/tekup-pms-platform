package com.pms.billing.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "avenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Avenant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 50)
    private String numero;

    @Column(length = 500)
    private String objet;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(name = "workload_days", precision = 10, scale = 2)
    private BigDecimal workloadDays;   // Impact en charge vendue (JH)

    @Column(name = "date_avenant", nullable = false)
    private LocalDate dateAvenant;
}

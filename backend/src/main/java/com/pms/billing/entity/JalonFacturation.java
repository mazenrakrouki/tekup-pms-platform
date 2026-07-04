package com.pms.billing.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "jalons_facturation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JalonFacturation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 255)
    private String label;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal pourcentage;

    @Column(precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(name = "date_prevue")
    private LocalDate datePrevue;

    @Column(name = "date_facture")
    private LocalDate dateFacture;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private JalonStatut statut = JalonStatut.PREVU;
}

package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Tarif TCC spécifique à une année (spec F-AFF-13 §6.3 règle 4 : TCC 2024 ≠ TCC 2025).
 * À défaut d'une entrée pour l'année d'imputation, le tarif de base de la ressource s'applique.
 */
@Entity
@Table(name = "tcc_annuels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TccAnnuel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    @Column(nullable = false)
    private Integer annee;

    @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyRate;

    @Column(name = "tcc_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal tccRate;
}

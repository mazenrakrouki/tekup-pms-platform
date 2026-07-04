package com.pms.billing.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "paiements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Paiement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jalon_id", nullable = false)
    private JalonFacturation jalon;

    @Column(name = "montant_recu", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantRecu;

    @Column(name = "date_paiement", nullable = false)
    private LocalDate datePaiement;

    @Column(length = 255)
    private String reference;
}

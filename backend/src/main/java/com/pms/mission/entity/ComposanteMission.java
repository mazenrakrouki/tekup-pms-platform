package com.pms.mission.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "composantes_mission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComposanteMission extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_id", nullable = false)
    private Mission mission;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_composante", nullable = false, length = 30)
    private TypeComposante typeComposante;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String devise = "TND";

    @Column(length = 500)
    private String description;
}

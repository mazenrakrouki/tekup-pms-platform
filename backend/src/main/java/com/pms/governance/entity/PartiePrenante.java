package com.pms.governance.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "parties_prenantes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartiePrenante extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 255)
    private String nom;

    @Column(length = 255)
    private String fonction;

    @Column(length = 255)
    private String email;

    @Column(length = 50)
    private String telephone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque influence = NiveauRisque.MOYEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque interet = NiveauRisque.MOYEN;
}

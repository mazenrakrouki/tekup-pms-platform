package com.pms.governance.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// JPA entity for one row of "parties_prenantes": a stakeholder on a project - name, job, contact
// details, plus influence and interet, the two judgements a manager uses to decide who to inform
// first when something changes. Table created by V11.

@Entity
// Table name differs from the class name ("parties_prenantes" vs "PartiePrenante").
@Table(name = "parties_prenantes")
// Lombok generates getters/setters, the no-args constructor JPA requires, an all-args
// constructor, and a builder. The builder matters here: four String fields in a row
// (nom, fonction, email, telephone) are easy to swap in a plain constructor call.
// The builder only covers fields declared in this class; id/createdAt/etc. come from BaseEntity.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// Brings in id, audit columns and the soft-delete "deleted" flag shared by every entity.
public class PartiePrenante extends BaseEntity {

    // LAZY to avoid an N+1 SELECT per stakeholder when listing; the repository JOIN FETCHes it
    // since open-in-view is false. No cascade: this screen must never write back to projects.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Mirrors "nom VARCHAR(255) NOT NULL"; ddl-auto=validate checks this against V11 at startup.
    @Column(nullable = false, length = 255)
    private String nom;

    // Job or role ("Directeur des achats", "Sponsor"...). Optional: may be known by name only at first.
    @Column(length = 255)
    private String fonction;

    // Format not checked here: @Email/@Size live on PartiePrenanteRequest for a clean 400 before save.
    @Column(length = 255)
    private String email;

    // Text, not a number type: phone numbers keep leading zeros and "+" signs.
    @Column(length = 50)
    private String telephone;

    // Reuses NiveauRisque (FAIBLE/MOYEN/ELEVE) rather than a fourth three-step enum, keeping one
    // vocabulary with the risk badges. Stored as the enum NAME so a later reordering can't silently
    // reinterpret old rows; also lets chk_pp_influence reject unknown values.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque influence = NiveauRisque.MOYEN;

    // How much this stakeholder cares, kept separate from influence: a busy director may have high
    // weight and low interest, a daily user the opposite - merging them would hide that difference.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque interet = NiveauRisque.MOYEN;
}

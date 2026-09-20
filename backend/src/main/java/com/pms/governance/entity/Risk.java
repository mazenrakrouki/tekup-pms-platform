package com.pms.governance.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// JPA entity for one row of "risks": an identified project risk - description, probabilite,
// impact, planMitigation and whether it is still open. Table created by V11. The register is
// also what proves, at project close, that a problem had been foreseen and planned for.

@Entity
// Table name differs from the class name ("risks" vs "Risk").
@Table(name = "risks")
// Lombok generates getters/setters, the no-args constructor JPA requires, an all-args
// constructor, and a builder. The builder matters here: probabilite and impact share the same
// type (NiveauRisque), so a plain constructor call could swap them without the compiler noticing.
// The builder only covers fields declared in this class; id/createdAt/etc. come from BaseEntity.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// Brings in id, audit columns and the soft-delete "deleted" flag shared by every entity.
// RiskService.delete() sets deleted=true rather than erasing the row, keeping it as evidence.
public class Risk extends BaseEntity {

    // LAZY to avoid an N+1 SELECT per risk when listing; the repository JOIN FETCHes it since
    // open-in-view is false. Also used by RiskService.loadRisk() to check the risk's project
    // matches the URL's projectId, so a risk can't be reached through another project's URL.
    // No cascade: this screen must never write back to projects.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Up to 1000 chars (not 255): a risk needs room to be understood by someone who wasn't in
    // the meeting. Mirrors V11; ddl-auto=validate checks this mapping against the table at startup.
    @Column(nullable = false, length = 1000)
    private String description;

    // Stored as the enum NAME (EnumType.STRING), not ORDINAL, so inserting a new value later can't
    // silently reinterpret old rows; also lets chk_risk_probabilite reject unknown values.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque probabilite = NiveauRisque.MOYEN;

    // Kept as a separate column rather than merged with probabilite: an unlikely risk that would
    // stop the whole project must stay visible, which a combined average would hide.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque impact = NiveauRisque.MOYEN;

    // Nullable: a risk is often written down before the mitigation plan is decided.
    @Column(name = "plan_mitigation", length = 1000)
    private String planMitigation;

    // Unlike Livrable/DemandeChangement, this comes straight from RiskRequest since closing a
    // risk is a manager's opinion, not a formal decision requiring its own guarded method.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private StatutRisque statut = StatutRisque.OUVERT;
}

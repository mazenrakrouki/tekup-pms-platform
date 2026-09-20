package com.pms.governance.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

// JPA entity for one row of "livrables": something the project must deliver to the client,
// with a title, an optional due date, and a status. It also holds the delivery state machine
// EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE that the service moves step by step. Table via V11.

@Entity
// Table name differs from the class name ("livrables" vs "Livrable").
@Table(name = "livrables")
// Lombok generates getters/setters, the no-args constructor JPA requires, an all-args
// constructor, and a builder (named args avoid swapping two adjacent Strings by mistake).
// The builder only covers fields declared in this class; id/createdAt/etc. come from BaseEntity.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// Brings in id, audit columns and the soft-delete "deleted" flag shared by every entity.
public class Livrable extends BaseEntity {

    // LAZY to avoid an N+1 SELECT per deliverable when listing; the repository JOIN FETCHes it
    // since open-in-view is false. No cascade: this screen must never write back to projects.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Mirrors "titre VARCHAR(255) NOT NULL"; ddl-auto=validate checks this against V11 at startup.
    @Column(nullable = false, length = 255)
    private String titre;

    // Optional: many deliverables are clear enough from their title alone.
    @Column(length = 1000)
    private String description;

    // Nullable: some deliverables have no agreed date yet; the repository sorts NULLS LAST.
    // LocalDate, not LocalDateTime: only the calendar day matters, avoiding time-zone drift.
    @Column(name = "date_echeance")
    private LocalDate dateEcheance;

    // Stored as the enum NAME (EnumType.STRING), not ORDINAL, so inserting a new value later can't
    // silently reinterpret old rows; also lets chk_livrable_statut reject unknown values.
    // Not part of LivrableRequest: only demarrer()/livrer()/valider() in the service move it.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private StatutLivrable statut = StatutLivrable.EN_ATTENTE;
}

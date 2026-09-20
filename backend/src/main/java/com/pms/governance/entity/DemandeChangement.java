package com.pms.governance.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

// JPA entity for one row of "demandes_changement": a change request asked for on a project and
// later approved or rejected. Carries the state machine EN_ATTENTE -> APPROUVE/REJETE that the
// service uses to refuse edits on an already-decided request. Table created by V11.

@Entity
// Table name differs from the class name ("demandes_changement" vs "DemandeChangement").
@Table(name = "demandes_changement")
// Lombok generates getters/setters, the no-args constructor JPA requires, an all-args
// constructor, and a builder (named args avoid swapping two adjacent String args by mistake).
// The builder only covers fields declared in this class; id/createdAt/etc. come from BaseEntity.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// Brings in id, audit columns and the soft-delete "deleted" flag shared by every entity.
public class DemandeChangement extends BaseEntity {

    // LAZY to avoid an N+1 SELECT per request when listing; the repository JOIN FETCHes it
    // since open-in-view is false. No cascade: this screen must never write back to projects.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Who asked for the change. LAZY + JOIN FETCH-ed for demandeur.getFullName() in the mapper.
    // No index on demandeur_id: the screen only ever queries by project, not by requester.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demandeur_id", nullable = false)
    private User demandeur;

    // Mirrors "titre VARCHAR(255) NOT NULL"; ddl-auto=validate checks this against V11 at startup.
    @Column(nullable = false, length = 255)
    private String titre;

    // Optional: a clear title is sometimes enough on its own.
    @Column(length = 1000)
    private String description;

    // Stored as the enum NAME (EnumType.STRING), not ORDINAL, so inserting a new value later can't
    // silently reinterpret old rows; also lets chk_dc_priorite reject unknown values.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private PrioriteChangement priorite = PrioriteChangement.NORMALE;

    // Not part of DemandeChangementRequest: a client cannot set this in the body. Only
    // approuver()/rejeter() in the service (MANAGE_GOVERNANCE) move it, so approval stays a decision.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private StatutChangement statut = StatutChangement.EN_ATTENTE;

    // LocalDate, not LocalDateTime: only the calendar day matters, and it avoids time-zone drift.
    @Column(name = "date_demande", nullable = false)
    private LocalDate dateDemande;

    // Null while EN_ATTENTE (null means "no decision yet"); the service sets it with statut together
    // so the two can never disagree.
    @Column(name = "date_decision")
    private LocalDate dateDecision;
}

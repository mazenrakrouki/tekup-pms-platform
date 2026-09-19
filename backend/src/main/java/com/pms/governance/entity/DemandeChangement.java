package com.pms.governance.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

// =============================================================================
// FILE: DemandeChangement.java   ("Change Request")
// =============================================================================
// WHAT THIS FILE IS
//   A JPA entity. One object of this class = one row of the SQL table
//   "demandes_changement". It holds a change request: somebody asks for a change
//   on a project, and later a manager approves it or rejects it.
//   (JPA = Java Persistence API, the standard Java way to map objects to database
//   rows. Hibernate is the library that does the real work behind JPA here.)
//
// WHERE IT SITS IN THE FLOW
//   HTTP call
//     -> DemandeChangementController   (URL /api/projects/{projectId}/...)
//     -> DemandeChangementService      (carries @PreAuthorize and @Transactional)
//     -> DemandeChangementRepository   (the SQL queries)
//     -> THIS CLASS                    (the row itself)
//     -> DemandeChangementMapper       (copies this into DemandeChangementResponse,
//                                       so the JSON sent out is never the entity)
//   This class points to two other entities: Project (which project) and User
//   (who asked). The table is created by the Flyway migration
//   V11__schema_governance.sql.
//
// WHY IT EXISTS
//   Remove this class and the change-request feature disappears. Hibernate would
//   have no mapping for the demandes_changement table, DemandeChangementRepository
//   would not even compile, and the governance screen would lose one of its tabs.
//   It also carries the small state machine EN_ATTENTE -> APPROUVE / REJETE that
//   the service uses to refuse any edit on a request that was already decided.
// =============================================================================

// @Entity tells Hibernate: "manage this class, it is a table".
// Why: without it Hibernate ignores the class completely.
// Example: the application would stop at startup with
// "Not a managed type: class com.pms.governance.entity.DemandeChangement".
@Entity
// @Table fixes the exact table name.
// Why: by default Hibernate would look for a table named after the class
// ("DemandeChangement"), but V11 created it as "demandes_changement".
// Example: without this line every query would hit a table that does not exist
// and fail with "relation does not exist".
@Table(name = "demandes_changement")
// Lombok writes the repetitive code for us at compile time, so this file stays
// short and readable:
//   @Getter / @Setter   -> generates getTitre(), setTitre(), and so on.
//   @NoArgsConstructor  -> the empty constructor that JPA REQUIRES. Hibernate
//                          creates the object empty first, then fills the fields.
//                          Without it the application fails at startup with
//                          "No default constructor for entity".
//   @AllArgsConstructor -> a constructor taking every field declared below.
//   @Builder            -> lets the service write
//                          DemandeChangement.builder().titre("x")...build()
//                          instead of a long constructor call where two String
//                          arguments next to each other are easy to swap by mistake.
// Careful: the builder only covers the fields declared IN THIS CLASS. id,
// createdAt, updatedAt and deleted come from BaseEntity and are filled by JPA and
// by the auditing listener, not by the builder.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// "extends BaseEntity" brings in id, created_at, updated_at, created_by,
// updated_by, the "deleted" soft-delete flag, and a safe equals/hashCode.
// Why: every table in this project is audited and soft-deleted the same way.
// Example: without it those six columns would have to be copied into every
// entity, and one forgotten "deleted" flag would let a removed change request
// come back in the list.
public class DemandeChangement extends BaseEntity {

    // Link to the project that owns this request.
    // @ManyToOne: many change requests point to one project.
    // @JoinColumn: the foreign key column is project_id and it can never be null
    // (same rule as fk_dc_project and NOT NULL in V11).
    // fetch = LAZY means the Project row is NOT read from the database until the
    // code really calls getProject(). Why: showing 50 change requests would
    // otherwise fire 50 extra SELECTs on the projects table (the "N+1 queries"
    // problem) and the page would crawl.
    // The price of LAZY: the object must still be inside its transaction when
    // getProject() is called. The application runs with open-in-view: false, so the
    // session closes as soon as the service method returns. That is exactly why
    // DemandeChangementRepository writes "JOIN FETCH d.project" in its queries -
    // without it the mapper would crash with LazyInitializationException.
    // No "cascade" is set on this link, and that is on purpose.
    // WHAT it means: saving or deleting a change request never writes anything
    // into the projects table.
    // WHY: the project is only the parent here; the change-request screen has no
    // right to change it.
    // Example of the damage a cascade would do: with cascade = CascadeType.ALL,
    // dcRepository.save(dc) would also push the attached Project back to the
    // database, so a project name changed by somebody else one second earlier
    // could be silently overwritten by the older copy carried here.
    //
    // Matching SQL index: V11 creates
    // "idx_dc_project ON demandes_changement(project_id) WHERE deleted = FALSE".
    // WHAT: a partial index, that is an index that stores only the rows still
    // alive.
    // WHY: every query of DemandeChangementRepository filters on project_id AND
    // deleted = false, which is exactly the pair this index holds.
    // Example without it: opening the change-request tab of one project would make
    // Postgres read every demandes_changement row of every project to find the few
    // that match.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // The person who asked for the change. Foreign key demandeur_id -> users(id).
    // Also LAZY, and also JOIN FETCH-ed by the repository, because the mapper needs
    // demandeur.getFullName() to fill demandeurFullName in the response.
    // nullable = false: a change request with no author could not be traced back to
    // anybody, and tracing decisions is the whole point of a governance log.
    // No "cascade" here either: editing or approving a change request must never
    // write into the users table.
    // Example of the damage: with cascade = CascadeType.ALL, saving a request
    // would also push the attached User back to the database, so an e-mail or a
    // job title changed meanwhile in the admin screen could be overwritten by the
    // older copy carried inside this request.
    //
    // Note there is no SQL index on demandeur_id, only on project_id
    // (idx_dc_project). WHY: the screen always asks for the requests of ONE
    // project, never for the requests of one person, so a second index would slow
    // down every insert and update for nothing. The day a "my requests" screen is
    // added, that index becomes necessary.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demandeur_id", nullable = false)
    private User demandeur;

    // Short title of the request. Mirrors "titre VARCHAR(255) NOT NULL" in V11.
    // Why repeat nullable and length here when the SQL already says it: the
    // application runs with ddl-auto: validate, so at startup Hibernate compares
    // this mapping with the real table and refuses to boot if they disagree.
    // Example: if a later migration shortened the column to VARCHAR(120) and this
    // line stayed at 255, the application would stop at once instead of failing
    // one day in production on a long title.
    @Column(nullable = false, length = 255)
    private String titre;

    // Free explanation text, optional, up to 1000 characters.
    // No "nullable = false" on purpose: a clear title is sometimes enough.
    @Column(length = 1000)
    private String description;

    // How urgent the request is.
    // @Enumerated(EnumType.STRING) stores the NAME of the value ("CRITIQUE") in the
    // column, not a number. Why: the JPA default is EnumType.ORDINAL, which stores
    // the position (0, 1, 2, 3). Example of the damage: if a new value were later
    // inserted at the top of PrioriteChangement, every old row would silently
    // change meaning - yesterday CRITIQUE would be read back as ELEVEE, and nothing
    // would warn anybody. Storing the text also lets the V11 check constraint
    // chk_dc_priorite refuse any value the application does not know.
    // length = 10 matches "priorite VARCHAR(10)"; the longest name, CRITIQUE, is
    // 8 characters.
    // @Builder.Default keeps the "= PrioriteChangement.NORMALE" default alive when
    // the object is built through the builder. Why: Lombok's builder ignores field
    // initial values unless this annotation is present. Example without it:
    // DemandeChangement.builder().titre("x").build() would leave priorite null and
    // the INSERT would be rejected by the NOT NULL column.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private PrioriteChangement priorite = PrioriteChangement.NORMALE;

    // Where the request stands: waiting, approved, or rejected.
    // Note that this field is NOT part of DemandeChangementRequest: a client cannot
    // send "statut": "APPROUVE" in the JSON body. Why: approving is a decision, not
    // a value you type. The only way to move it is approuver() or rejeter() in
    // DemandeChangementService, both guarded by
    // @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')").
    // Example of the hole this closes: otherwise anyone allowed to edit a request
    // could approve their own change just by adding one line to the request body.
    // length = 15 matches "statut VARCHAR(15)"; EN_ATTENTE is 10 characters.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private StatutChangement statut = StatutChangement.EN_ATTENTE;

    // The day the change was asked for. Sent by the client and required
    // (@NotNull in the request record, NOT NULL in the table).
    // LocalDate and not LocalDateTime: only the calendar day matters here, and a
    // plain date carries no time zone, so every user reads the same day.
    // Example of the bug avoided: with a timestamp, a request saved at 23:30 in
    // Tunis could be shown as the day before to a reader in another time zone.
    @Column(name = "date_demande", nullable = false)
    private LocalDate dateDemande;

    // The day the request was approved or rejected.
    // Left null on purpose while the request is still EN_ATTENTE: here null IS the
    // information "no decision yet".
    // The service sets it with LocalDate.now() inside the same method that sets
    // statut, so the pair (statut, dateDecision) can never disagree.
    // Example of what would be missing without this column: the screen could say a
    // request is approved but never say when, so nobody could measure how long
    // decisions take.
    @Column(name = "date_decision")
    private LocalDate dateDecision;
}

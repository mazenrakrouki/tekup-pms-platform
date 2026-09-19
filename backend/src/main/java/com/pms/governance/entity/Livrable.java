package com.pms.governance.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

// =============================================================================
// FILE: Livrable.java   ("Deliverable")
// =============================================================================
// WHAT THIS FILE IS
//   A JPA entity. One object of this class = one row of the SQL table
//   "livrables". It describes something the project has promised to deliver to
//   the client: a title, an optional due date, and where it stands.
//   (JPA = Java Persistence API, the standard Java way to map objects to database
//   rows. Hibernate is the library that does the real work behind JPA here.)
//
// WHERE IT SITS IN THE FLOW
//   HTTP call
//     -> LivrableController    (URL /api/projects/{projectId}/...)
//     -> LivrableService       (carries @PreAuthorize and @Transactional)
//     -> LivrableRepository    (the SQL queries)
//     -> THIS CLASS            (the row itself)
//     -> LivrableMapper        (copies this into LivrableResponse, so the JSON
//                               sent to the browser is never the entity)
//   It points to one other entity: Project. The table is created by the Flyway
//   migration V11__schema_governance.sql.
//
// WHY IT EXISTS
//   Without this class there is no deliverable list: Hibernate would have no
//   mapping for the livrables table, LivrableRepository would not compile, and
//   the governance screen would lose its deliverables tab.
//   It is also the only place that holds the delivery state, which the service
//   moves step by step: EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE.
// =============================================================================

// @Entity tells Hibernate: "manage this class, it is a table".
// Why: without it Hibernate ignores the class completely.
// Example: the application would stop at startup with
// "Not a managed type: class com.pms.governance.entity.Livrable".
@Entity
// @Table fixes the exact table name.
// Why: the default would be the class name ("Livrable"), but V11 created the
// table as "livrables" (plural, lower case).
// Example: without this line every query would fail with "relation does not exist".
@Table(name = "livrables")
// Lombok writes the repetitive code at compile time:
//   @Getter / @Setter   -> generates getTitre(), setStatut(), and so on.
//   @NoArgsConstructor  -> the empty constructor that JPA REQUIRES: Hibernate
//                          builds the object empty, then fills the fields.
//                          Without it the application fails at startup with
//                          "No default constructor for entity".
//   @AllArgsConstructor -> a constructor taking every field declared below.
//   @Builder            -> lets LivrableService write
//                          Livrable.builder().titre("x")...build(), which is much
//                          safer than a constructor call where two Strings sitting
//                          next to each other are easy to swap by mistake.
// Careful: the builder only covers the fields declared IN THIS CLASS. id,
// createdAt, updatedAt and deleted come from BaseEntity and are filled by JPA and
// by the auditing listener.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// "extends BaseEntity" brings in id, created_at, updated_at, created_by,
// updated_by, the "deleted" soft-delete flag, and a safe equals/hashCode.
// Why: rows are never really erased in this application, they are marked deleted,
// and the repository queries filter on "deleted = false".
// Example of what it protects: a deliverable removed by mistake can still be
// found in the database for an audit, instead of being gone forever.
public class Livrable extends BaseEntity {

    // Link to the project that owns this deliverable.
    // @ManyToOne: many deliverables point to one project.
    // @JoinColumn: the foreign key column is project_id, and null is forbidden
    // (same rule as fk_livrable_project and NOT NULL in V11).
    // fetch = LAZY means the Project row is read only when getProject() is really
    // called. Why: listing 30 deliverables would otherwise fire 30 extra SELECTs
    // on the projects table (the "N+1 queries" problem).
    // The price of LAZY: the object must still be inside its transaction when the
    // link is used, and this application sets open-in-view: false, so the session
    // closes when the service method returns. That is why LivrableRepository writes
    // "JOIN FETCH l.project" - without it the mapper, which reads project.getCode()
    // for projectCode, would crash with LazyInitializationException.
    // No "cascade" is set on this link, and that is on purpose.
    // WHAT it means: saving or deleting a deliverable never writes anything into
    // the projects table.
    // WHY: the project is only the parent here; the deliverable screen has no
    // right to change it.
    // Example of the damage a cascade would do: with cascade = CascadeType.ALL,
    // livrableRepository.save(livrable) would also push the attached Project back
    // to the database, so a project name changed by somebody else one second
    // earlier could be silently overwritten by the older copy carried here.
    //
    // Matching SQL index: V11 creates
    // "idx_livrable_project ON livrables(project_id) WHERE deleted = FALSE".
    // WHAT: a partial index, that is an index that stores only the rows still
    // alive.
    // WHY: every query of LivrableRepository filters on project_id AND
    // deleted = false, which is exactly the pair this index holds.
    // Example without it: opening the deliverables tab of one project would make
    // Postgres read every livrables row of every project to find the few that
    // match.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Name of the deliverable. Mirrors "titre VARCHAR(255) NOT NULL" in V11.
    // Why repeat nullable and length here: the application runs with
    // ddl-auto: validate, so Hibernate compares this mapping with the real table at
    // startup and refuses to boot if they disagree.
    // Example: if a later migration shortened the column to VARCHAR(120) and this
    // line stayed at 255, the application would stop at once instead of failing one
    // day in production on a long title.
    @Column(nullable = false, length = 255)
    private String titre;

    // Optional detail text, up to 1000 characters.
    // No "nullable = false": many deliverables are clear enough from their title.
    @Column(length = 1000)
    private String description;

    // Due date. @Column(name = ...) is needed because the Java name dateEcheance
    // and the SQL name date_echeance are spelled differently.
    // Nullable on purpose: some deliverables have no agreed date yet, and null here
    // means exactly that. LivrableRepository sorts with "NULLS LAST" so those rows
    // land at the bottom of the list instead of on top.
    // LocalDate and not LocalDateTime: only the calendar day matters, and a plain
    // date carries no time zone, so every user sees the same day.
    @Column(name = "date_echeance")
    private LocalDate dateEcheance;

    // Where the deliverable stands.
    // @Enumerated(EnumType.STRING) stores the NAME ("LIVRE") in the column, not a
    // number. Why: the JPA default is EnumType.ORDINAL, which stores the position
    // (0, 1, 2, 3). Example of the damage: adding a new value in the middle of
    // StatutLivrable would silently change the meaning of every existing row - a
    // deliverable marked LIVRE could read back as EN_COURS. Storing the text also
    // lets the V11 check constraint chk_livrable_statut refuse unknown values.
    // length = 15 matches "statut VARCHAR(15)"; the longest name, EN_ATTENTE, is
    // 10 characters.
    // @Builder.Default keeps the "= StatutLivrable.EN_ATTENTE" default alive when
    // the object is built through the builder. Why: Lombok's builder ignores field
    // initial values unless this annotation is present. Example without it:
    // Livrable.builder().titre("x").build() would leave statut null and the INSERT
    // would be rejected by the NOT NULL column.
    // This field is not part of LivrableRequest, so a client cannot set it directly
    // in the JSON body: it only moves through the dedicated service methods
    // demarrer(), livrer() and valider(), which check the current value first.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private StatutLivrable statut = StatutLivrable.EN_ATTENTE;
}

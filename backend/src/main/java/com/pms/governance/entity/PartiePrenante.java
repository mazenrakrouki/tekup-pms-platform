package com.pms.governance.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// =============================================================================
// FILE: PartiePrenante.java   ("Stakeholder")
// =============================================================================
// WHAT THIS FILE IS
//   A JPA entity. One object of this class = one row of the SQL table
//   "parties_prenantes". It holds one person or organisation involved in a
//   project: name, job, contact details, plus two judgements - how much weight
//   this person has (influence) and how much they care (interet).
//   (JPA = Java Persistence API, the standard Java way to map objects to database
//   rows. Hibernate is the library that does the real work behind JPA here.)
//
// WHERE IT SITS IN THE FLOW
//   HTTP call
//     -> PartiePrenanteController   (URL /api/projects/{projectId}/...)
//     -> PartiePrenanteService      (carries @PreAuthorize and @Transactional)
//     -> PartiePrenanteRepository   (the SQL queries)
//     -> THIS CLASS                 (the row itself)
//     -> PartiePrenanteMapper       (copies this into PartiePrenanteResponse, so
//                                    the JSON sent out is never the entity)
//   It points to one other entity: Project. The table is created by the Flyway
//   migration V11__schema_governance.sql.
//
// WHY IT EXISTS
//   Without this class there is no stakeholder register: Hibernate would have no
//   mapping for the parties_prenantes table, PartiePrenanteRepository would not
//   compile, and the governance screen would lose its stakeholders tab.
//   The pair (influence, interet) is what lets a project manager decide who must
//   be informed first when something changes.
// =============================================================================

// @Entity tells Hibernate: "manage this class, it is a table".
// Why: without it Hibernate ignores the class completely.
// Example: the application would stop at startup with
// "Not a managed type: class com.pms.governance.entity.PartiePrenante".
@Entity
// @Table fixes the exact table name.
// Why: the default would be the class name ("PartiePrenante"), but V11 created
// the table as "parties_prenantes".
// Example: without this line every query would fail with "relation does not exist".
@Table(name = "parties_prenantes")
// Lombok writes the repetitive code at compile time:
//   @Getter / @Setter   -> generates getNom(), setEmail(), and so on.
//   @NoArgsConstructor  -> the empty constructor that JPA REQUIRES: Hibernate
//                          builds the object empty, then fills the fields.
//                          Without it the application fails at startup with
//                          "No default constructor for entity".
//   @AllArgsConstructor -> a constructor taking every field declared below.
//   @Builder            -> lets PartiePrenanteService write
//                          PartiePrenante.builder().nom("x")...build().
//                          This matters here: the class has four String fields in
//                          a row (nom, fonction, email, telephone), and in a plain
//                          constructor call it is very easy to put the phone number
//                          where the e-mail belongs. The builder names each value.
// Careful: the builder only covers the fields declared IN THIS CLASS. id,
// createdAt, updatedAt and deleted come from BaseEntity.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// "extends BaseEntity" brings in id, created_at, updated_at, created_by,
// updated_by, the "deleted" soft-delete flag, and a safe equals/hashCode.
// Why: rows are never really erased here, they are flagged, and every repository
// query filters on "deleted = false".
// Example: a stakeholder removed by mistake can still be recovered for an audit.
public class PartiePrenante extends BaseEntity {

    // Link to the project this stakeholder belongs to.
    // @ManyToOne: many stakeholders point to one project.
    // @JoinColumn: the foreign key column is project_id and null is forbidden
    // (same rule as fk_pp_project and NOT NULL in V11).
    // fetch = LAZY means the Project row is read only when getProject() is really
    // called. Why: listing 20 stakeholders would otherwise fire 20 extra SELECTs on
    // the projects table (the "N+1 queries" problem).
    // The price of LAZY: the link can only be used while the transaction is open,
    // and this application sets open-in-view: false, so the session closes when the
    // service method returns. That is why PartiePrenanteRepository writes
    // "JOIN FETCH p.project" - without it the mapper, which reads project.getCode()
    // to fill projectCode, would crash with LazyInitializationException.
    // No "cascade" is set on this link, and that is on purpose.
    // WHAT it means: saving or deleting a stakeholder never writes anything into
    // the projects table.
    // WHY: the project is only the parent here; the stakeholder screen has no
    // right to change it.
    // Example of the damage a cascade would do: with cascade = CascadeType.ALL,
    // saving a stakeholder would also push the attached Project back to the
    // database, so a project name changed by somebody else one second earlier
    // could be silently overwritten by the older copy carried here.
    //
    // Matching SQL index: V11 creates
    // "idx_pp_project ON parties_prenantes(project_id) WHERE deleted = FALSE".
    // WHAT: a partial index, that is an index that stores only the rows still
    // alive.
    // WHY: every query of PartiePrenanteRepository filters on project_id AND
    // deleted = false, which is exactly the pair this index holds.
    // Example without it: opening the stakeholders tab of one project would make
    // Postgres read every parties_prenantes row of every project to find the few
    // that match.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Name of the person or the organisation. Mirrors "nom VARCHAR(255) NOT NULL".
    // Why repeat nullable and length here: the application runs with
    // ddl-auto: validate, so Hibernate compares this mapping with the real table at
    // startup and refuses to boot if the two disagree.
    // Example: a stakeholder row with no name would be useless in the register, so
    // the database refuses it and @NotBlank in PartiePrenanteRequest stops an empty
    // string even earlier, before the service is reached.
    @Column(nullable = false, length = 255)
    private String nom;

    // Job or role of the person ("Directeur des achats", "Sponsor"...). Optional:
    // an external contact may be known only by name at first.
    @Column(length = 255)
    private String fonction;

    // Contact e-mail. Optional, and NOT checked for format at this level: the
    // format rule lives on PartiePrenanteRequest, which carries @Email and
    // @Size(max = 255).
    // Why the check sits there and not here: Bean Validation on the request rejects
    // a bad address with a clean 400 error before anything is saved. Example: with
    // only the column rule, "not-an-address" would be stored happily and the
    // problem would only appear the day somebody tries to contact that person.
    @Column(length = 255)
    private String email;

    // Phone number kept as text, up to 50 characters. Never a number type.
    // Why: phone numbers have leading zeros, plus signs and spaces.
    // Example: stored as a number, "+216 71 000 000" would lose the "+" and
    // "0021671000000" would become 21671000000.
    @Column(length = 50)
    private String telephone;

    // How much weight this stakeholder carries on the project.
    // It reuses the NiveauRisque enum (FAIBLE / MOYEN / ELEVE) instead of declaring
    // a fourth three-step enum. Why: it is the same three-step scale, and the
    // front end already translates those three keys ("riskLevel.FAIBLE"...), so
    // reusing it keeps one single vocabulary in the interface.
    // @Enumerated(EnumType.STRING) stores the NAME ("ELEVE"), not a number. Why:
    // the JPA default is EnumType.ORDINAL, which stores the position (0, 1, 2).
    // Example of the damage: adding a value at the top of NiveauRisque would
    // silently turn every stored ELEVE into MOYEN. Storing the text also lets the
    // V11 check constraint chk_pp_influence refuse unknown values.
    // length = 10 matches "influence VARCHAR(10)"; the longest name, FAIBLE, is 6.
    // @Builder.Default keeps the "= NiveauRisque.MOYEN" default alive when the
    // object is built through the builder. Why: Lombok's builder ignores field
    // initial values unless this annotation is present. Example without it:
    // PartiePrenante.builder().nom("x").build() would leave influence null and the
    // INSERT would be rejected by the NOT NULL column.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque influence = NiveauRisque.MOYEN;

    // How much this stakeholder cares about the project. Same scale, same storage
    // rules and same default as "influence" just above.
    // Why the two are kept apart: a person can have a lot of weight and little
    // interest (a busy director) or a lot of interest and little weight (a daily
    // user). Example of what would be lost if they were merged into one score:
    // the manager could not tell which of those two people must be pushed to react
    // and which one only needs to be kept informed.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque interet = NiveauRisque.MOYEN;
}

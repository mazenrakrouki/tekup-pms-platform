package com.pms.governance.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// =============================================================================
// FILE: Risk.java   ("Risque" - one line of the project risk register)
// =============================================================================
// WHAT THIS FILE IS
//   A JPA entity. One object of this class = one row of the SQL table "risks".
//   It holds one identified risk on a project: what could go wrong, how likely it
//   is (probabilite), how bad it would be (impact), what is planned to reduce it
//   (planMitigation), and whether it is still open.
//   (JPA = Java Persistence API, the standard Java way to map objects to database
//   rows. Hibernate is the library that does the real work behind JPA here.)
//
// WHERE IT SITS IN THE FLOW
//   HTTP call
//     -> RiskController    (URL /api/projects/{projectId}/risks)
//     -> RiskService       (carries @PreAuthorize and @Transactional)
//     -> RiskRepository    (the SQL queries)
//     -> THIS CLASS        (the row itself)
//     -> RiskMapper        (copies this into RiskResponse, so the JSON sent to the
//                           browser is never the entity)
//   It points to one other entity: Project. The table is created by the Flyway
//   migration V11__schema_governance.sql.
//
// WHY IT EXISTS
//   Without this class there is no risk register: Hibernate would have no mapping
//   for the risks table, RiskRepository would not compile, and the governance
//   screen would lose its risks tab.
//   The register is also what proves, at the end of the project, that a problem
//   had been seen and planned for before it happened.
// =============================================================================

// @Entity tells Hibernate: "manage this class, it is a table".
// Why: without it Hibernate ignores the class completely.
// Example: the application would stop at startup with
// "Not a managed type: class com.pms.governance.entity.Risk".
@Entity
// @Table fixes the exact table name. The class is "Risk" and the table is "risks"
// (plural), so the two names must be linked explicitly.
// Example: without this line every query would fail with "relation does not exist".
@Table(name = "risks")
// Lombok writes the repetitive code at compile time:
//   @Getter / @Setter   -> generates getDescription(), setStatut(), and so on.
//   @NoArgsConstructor  -> the empty constructor that JPA REQUIRES: Hibernate
//                          builds the object empty, then fills the fields.
//                          Without it the application fails at startup with
//                          "No default constructor for entity".
//   @AllArgsConstructor -> a constructor taking every field declared below.
//   @Builder            -> lets RiskService write
//                          Risk.builder().description("x").impact(ELEVE)...build().
//                          This matters here: probabilite and impact have the very
//                          same type (NiveauRisque), so in a plain constructor call
//                          nothing would stop somebody from swapping them, and the
//                          compiler would not complain. The builder names each one.
// Careful: the builder only covers the fields declared IN THIS CLASS. id,
// createdAt, updatedAt and deleted come from BaseEntity.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// "extends BaseEntity" brings in id, created_at, updated_at, created_by,
// updated_by, the "deleted" soft-delete flag, and a safe equals/hashCode.
// Why this matters for risks in particular: RiskService.delete() does not erase
// the row, it sets deleted = true. A closed or removed risk stays in the database
// as evidence, and createdAt is also what RiskRepository sorts the list on.
public class Risk extends BaseEntity {

    // Link to the project this risk belongs to.
    // @ManyToOne: many risks point to one project.
    // @JoinColumn: the foreign key column is project_id and null is forbidden
    // (same rule as fk_risk_project and NOT NULL in V11).
    // fetch = LAZY means the Project row is read only when getProject() is really
    // called. Why: listing 40 risks would otherwise fire 40 extra SELECTs on the
    // projects table (the "N+1 queries" problem).
    // The price of LAZY: the link can only be used while the transaction is open,
    // and this application sets open-in-view: false, so the session closes when the
    // service method returns. That is why RiskRepository writes "JOIN FETCH
    // r.project" - without it the mapper, which reads project.getCode() to fill
    // projectCode, would crash with LazyInitializationException.
    // This field is also used by RiskService.loadRisk(): it compares
    // risk.getProject().getId() with the projectId in the URL and answers "not
    // found" when they differ, so risk 42 of project A cannot be reached through
    // the URL of project B.
    // No "cascade" is set on this link, and that is on purpose.
    // WHAT it means: saving or deleting a risk never writes anything into the
    // projects table.
    // WHY: the project is only the parent here; the risk screen has no right to
    // change it.
    // Example of the damage a cascade would do: with cascade = CascadeType.ALL,
    // riskRepository.save(risk) would also push the attached Project back to the
    // database, so a project name changed by somebody else one second earlier
    // could be silently overwritten by the older copy carried inside this risk.
    //
    // Matching SQL index: V11 creates
    // "idx_risk_project ON risks(project_id) WHERE deleted = FALSE".
    // WHAT: a partial index, that is an index that stores only the rows still
    // alive.
    // WHY: every query of RiskRepository filters on project_id AND
    // deleted = false, which is exactly the pair this index holds.
    // Example without it: opening the risk tab of one project would make Postgres
    // read every risk row of every project to find the few that match.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // What the risk is, in plain words. Required, up to 1000 characters
    // ("description VARCHAR(1000) NOT NULL" in V11).
    // Why 1000 and not 255: a risk has to be written so that somebody who was not
    // in the meeting still understands it - "the client has not yet confirmed the
    // production server, so the go-live date may slip" needs room.
    // Why repeat nullable and length here: the application runs with
    // ddl-auto: validate, so Hibernate compares this mapping with the real table at
    // startup and refuses to boot if the two disagree.
    @Column(nullable = false, length = 1000)
    private String description;

    // How likely the risk is to happen: FAIBLE, MOYEN or ELEVE.
    // @Enumerated(EnumType.STRING) stores the NAME ("ELEVE") in the column, not a
    // number. Why: the JPA default is EnumType.ORDINAL, which stores the position
    // (0, 1, 2). Example of the damage: if a value were later added at the top of
    // NiveauRisque, every existing row would silently change meaning and a risk
    // rated ELEVE would read back as MOYEN - on a risk register that is dangerous,
    // because nobody would notice. Storing the text also lets the V11 check
    // constraint chk_risk_probabilite refuse any value the application does not know.
    // length = 10 matches "probabilite VARCHAR(10)"; the longest name, FAIBLE, is 6.
    // @Builder.Default keeps the "= NiveauRisque.MOYEN" default alive when the
    // object is built through the builder. Why: Lombok's builder ignores field
    // initial values unless this annotation is present. Example without it:
    // Risk.builder().description("x").build() would leave probabilite null and the
    // INSERT would be rejected by the NOT NULL column.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque probabilite = NiveauRisque.MOYEN;

    // How much harm the risk would do if it happened. Same scale, same storage
    // rules and same default as "probabilite" just above.
    // Why the two are kept as separate columns instead of one combined score: they
    // are decided separately and they are treated differently. Example: a risk that
    // is very unlikely but would stop the whole project still has to be visible; a
    // single average would hide it behind a medium value.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private NiveauRisque impact = NiveauRisque.MOYEN;

    // What is planned to reduce the risk, or what to do if it happens.
    // @Column(name = ...) is needed because the Java name planMitigation and the
    // SQL name plan_mitigation are spelled differently.
    // Nullable on purpose: a risk is often written down first and the plan is
    // decided later. Null here means "no plan agreed yet", which is itself useful
    // information for a review meeting.
    @Column(name = "plan_mitigation", length = 1000)
    private String planMitigation;

    // Where the risk stands: OUVERT, MITIGE or FERME.
    // Unlike the deliverable and the change request, this value comes straight from
    // RiskRequest, so the client sets it in the same call that edits the rest.
    // Why it is acceptable here: closing a risk is a simple opinion of the project
    // manager, not a formal decision, and the write path is already protected by
    // @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')") on the service method plus
    // the project scope check of ADR-021 on the URL.
    // length = 10 matches "statut VARCHAR(10)"; the longest name, OUVERT, is 6.
    // @Builder.Default keeps the "= StatutRisque.OUVERT" default alive when the
    // object is built through the builder; without it a new risk built with the
    // builder would have a null statut and the INSERT would fail.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private StatutRisque statut = StatutRisque.OUVERT;
}

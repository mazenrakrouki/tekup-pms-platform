package com.pms.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/*
 * =========================================================================
 * WHAT THIS FILE IS
 *   The parent class of every database entity of the application. It does not
 *   own a table of its own. It only holds the six columns that all the entity
 *   tables repeat: the primary key "id", the two dates created_at /
 *   updated_at, the two names created_by / updated_by, and the soft-delete
 *   flag "deleted". Every entity class of the project extends it, for example
 *   Project, LigneDi, User, Role, Permission, Sprint, BacklogItem, Mission,
 *   Risk, Paiement, TccAnnuel, SnapshotKpi.
 *
 * WHERE IT SITS IN THE FLOW
 *   Controller -> Service (this is where @PreAuthorize sits) -> Repository
 *     -> the entity class, for instance Sprint
 *     -> THIS CLASS, which Sprint extends and which brings the id, the audit
 *        columns and the "deleted" flag into the "sprints" table.
 *   Two Spring beans work together with this file, and nothing happens
 *   without them:
 *     - com.pms.shared.config.JpaConfig carries
 *       @EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware"),
 *       which switches the auditing machinery on;
 *     - com.pms.shared.config.SpringSecurityAuditorAware answers the question
 *       "who is writing right now?". It reads the logged-in user out of the
 *       Spring Security context and returns that username, or the literal
 *       "system" when nobody is logged in (application start-up, seeders,
 *       scheduled jobs). That answer is what lands in created_by / updated_by.
 *   The columns themselves are created by the Flyway migrations: id,
 *   created_at, updated_at and deleted since V1, and created_by / updated_by
 *   added for every table later by V19 (audit point H-6).
 *
 * WHY IT EXISTS
 *   Delete this class and three things break at once.
 *   1. Every entity class would have to declare its own id, its own two dates
 *      and its own deleted flag again. One forgotten field in one entity
 *      means a NOT NULL column that Hibernate never fills, and the first
 *      insert into that table fails.
 *   2. The audit trail disappears. "Who changed this DI line, and when?" is a
 *      question the jury and the company both ask, and created_by /
 *      updated_by are the only place the answer is stored.
 *   3. equals() and hashCode() would fall back to the ones of Object, which
 *      compare memory addresses. The same row loaded twice in two different
 *      places would then look like two different objects, and
 *      Role.permissions (a HashSet) could hold the same permission twice.
 *
 * TWO RULES THAT APPLY TO THE WHOLE FILE
 *   1. No security code here. Authorization in this project is dynamic and
 *      permission-based: @PreAuthorize("hasAuthority('...')") sits on the
 *      SERVICE methods, never on a controller and never on an entity. On top
 *      of that, ProjectScopeInterceptor checks the project perimeter for
 *      every URL shaped /api/projects/{id}/** (ADR-021), because holding the
 *      permission is not enough - the caller must also be allowed on THAT
 *      project. An entity has no idea who is calling, so it must not decide.
 *   2. Soft delete is not automatic. Setting deleted = true is all the
 *      services do, but Hibernate does NOT hide those rows by itself: this
 *      project deliberately uses no @Where / @SQLRestriction filter. Every
 *      repository query writes "AND x.deleted = false" by hand (see
 *      SprintRepository, BacklogItemRepository, ProjectRepository). Calling
 *      the ready-made findAll() of Spring Data instead brings deleted rows
 *      back onto the screen.
 * =========================================================================
 */

/**
 * Shared parent of every entity: primary key, audit columns, soft-delete flag.
 *
 * <p>Why the class is written this way, annotation by annotation:
 *
 * <p>- {@code @MappedSuperclass} means "these fields belong to the children,
 * not to me". Hibernate copies id, created_at, updated_at, created_by,
 * updated_by and deleted into each child table (sprints, projects, users) and
 * creates NO table called base_entity. The obvious alternative,
 * {@code @Entity} plus {@code @Inheritance}, would either put every entity
 * type into one shared table or force a join on every single read. Without
 * either annotation Hibernate simply ignores this class, so Sprint would have
 * no {@code @Id} at all and the application would refuse to start with "No
 * identifier specified for entity".
 *
 * <p>- {@code @EntityListeners(AuditingEntityListener.class)} plugs the Spring
 * Data listener onto the children. That listener is the code that actually
 * fills the four audit fields just before the INSERT or the UPDATE is sent.
 * Without this line the {@code @CreatedDate} and {@code @CreatedBy}
 * annotations below are only decoration: created_at would stay null, and
 * because that column is declared NOT NULL in V1, saving any row would fail
 * with a not-null constraint violation.
 *
 * <p>- {@code @Getter} / {@code @Setter} (Lombok) generate getId(),
 * setDeleted(...) and the rest at compile time. The services use them, for
 * example {@code item.setDeleted(true)} in BacklogItemService.delete().
 * Hibernate itself does not need them: the {@code @Id} is placed on a field
 * below, so Hibernate reads and writes the fields directly.
 *
 * <p>- {@code @NoArgsConstructor} gives an empty constructor. JPA requires
 * one: to read a row, Hibernate first builds an empty object and only then
 * fills the fields. The child classes declare {@code @AllArgsConstructor} and
 * {@code @Builder} of their own, and as soon as any constructor annotation is
 * present Lombok stops adding the empty one by itself - hence this explicit
 * annotation on the parent that every child inherits from.
 *
 * <p>The class is {@code abstract}: nobody may write
 * {@code new BaseEntity()}. There is no table behind it, so such an object
 * could never be saved; making the class abstract turns that mistake into a
 * compilation error instead of a confusing failure at runtime.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public abstract class BaseEntity {

    // @Id marks the primary key. Every entity needs exactly one; without it
    // Hibernate cannot tell two rows apart and refuses to start.
    //
    // @GeneratedValue(strategy = IDENTITY) means "the database gives the
    // number, not Java". It matches the BIGSERIAL columns written in the
    // Flyway migrations (V1 and the ones after it): PostgreSQL takes the next
    // value from its own counter, and Hibernate reads that value back right
    // after the INSERT and puts it into this field.
    // Why IDENTITY and not a number chosen in Java: the schema belongs to
    // Flyway and is only checked at start-up (application.yml runs with
    // ddl-auto=validate, ADR-019), and the columns are already BIGSERIAL.
    // Picking the id in Java instead would let two users pick the same number
    // at the same moment, and the second INSERT would be rejected with a
    // duplicate-key error on the primary key.
    // The price of IDENTITY is that Hibernate cannot group several INSERTs
    // into one batch, because it has to ask the database for each id. That is
    // accepted here: the application never inserts thousands of rows at once.
    //
    // The type is Long (an object), not long (a primitive): a brand new
    // object that has not been saved yet must be able to say "I have no id".
    // A primitive long would hold 0 instead, and 0 looks like a real id, which
    // would confuse both equals() below and any code asking "was this saved?".
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @CreatedDate asks the auditing listener to stamp the current date and
    // time once, at the very first save. It is never touched again.
    //
    // updatable = false is what makes "never again" true: Hibernate leaves
    // this column out of every UPDATE statement it builds. Without it, a
    // service that reads a project, overwrites createdAt by accident and saves
    // would silently rewrite the creation date, and the audit trail would then
    // claim that a project created in January was created today.
    //
    // nullable = false mirrors the NOT NULL written in the migrations. It is
    // not a second check at runtime; it is how the start-up validation of
    // Hibernate (ddl-auto=validate) knows the Java side and the real schema
    // agree. A mismatch here stops the application at boot instead of failing
    // later on a random insert.
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // @LastModifiedDate is stamped again at every save, so this column always
    // holds the date of the last change. This is what lets a screen sort by
    // "most recently touched", and what shows an auditor that a DI line was
    // edited after the quote had been validated.
    // Note that updatable = false is absent here, and that is on purpose: this
    // column MUST take part in every UPDATE. With updatable = false it would
    // freeze at the creation date and would always equal created_at.
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // @CreatedBy stores WHO created the row. The value does not come from the
    // entity: the auditing listener asks SpringSecurityAuditorAware, which
    // returns the username of the logged-in caller, or the literal "system"
    // when there is no logged-in user (start-up seeders, scheduled jobs).
    // AgileDemoSeeder relies on exactly that difference: it only replaces rows
    // whose created_by is "system" or null, so it never destroys a board that
    // a real person built by hand.
    //
    // updatable = false: the author of a row cannot be rewritten later.
    // Without it, the first edit made by somebody else would erase the real
    // author, and the audit trail would blame the wrong person.
    //
    // There is no nullable = false here, on purpose: these two name columns
    // were added long after the first tables, by V19 (audit point H-6), and
    // the rows that already existed at that moment hold NULL. Declaring the
    // field NOT NULL would make the start-up validation of Hibernate fail
    // against a real database. length = 255 matches the VARCHAR(255) of that
    // same migration, so the two descriptions of the column agree.
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    // @LastModifiedBy stores WHO made the last change, refreshed at every
    // save. Together with updated_at it answers the one question that really
    // matters when the DI (Devis Interne, the internal quote) is audited:
    // "who changed this amount, and when?". Without this column the history
    // would show that a figure moved but never say by whose hand.
    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    // The soft-delete flag. "Soft delete" means the row is never removed from
    // the table: deleting only writes deleted = true, and the queries skip it.
    //
    // Why not a real SQL DELETE: the rows of this application point at each
    // other and they are accounting history. Erasing a mission would break
    // every plan de charge, every KPI snapshot and every invoice milestone
    // pointing at it, and the figures of past years would change afterwards.
    //
    // = false is a plain Java field initialiser, so a freshly built object is
    // alive from the start, before the column default (DEFAULT FALSE in V1)
    // has any say. Without it the field would be false anyway for a primitive
    // boolean, but writing it keeps the intention visible next to the column.
    //
    // nullable = false: the column is NOT NULL in the schema. Allowing null
    // would create a third state, and in SQL "AND deleted = false" is NOT true
    // for a NULL, so such a row would quietly vanish from every list in the
    // application while still sitting in the table.
    //
    // Two consequences worth remembering:
    //   - nothing filters automatically; every query writes the condition by
    //     hand (see rule 2 in the file header);
    //   - a deleted row still occupies its unique values. That is why V18
    //     replaced the plain UNIQUE constraints on users.email and
    //     projects.code by partial unique indexes "WHERE deleted = FALSE":
    //     without that change, an email address could never be used again
    //     after the user holding it had been deleted.
    @Column(nullable = false)
    private boolean deleted = false;

    /**
     * Says whether two Java objects stand for the same database row.
     *
     * <p>Why it has to be written by hand: the equals() inherited from Object
     * compares memory addresses, so the same row loaded twice in two different
     * places would count as two different objects. Role.permissions is a
     * {@code HashSet<Permission>}, and it would then be able to hold the same
     * permission twice, which would show a duplicated line in the RBAC screen.
     *
     * <p>Why the comparison uses the id only and not all the fields: the id is
     * the single thing that identifies a row. Comparing the other fields would
     * mean that renaming a project turns it into a different project.
     *
     * <p>Why the extra {@code id != null} test: Hibernate only assigns the id
     * at the moment of the INSERT, so before the first save the id is null.
     * Without that test {@code null.equals(...)} would throw a
     * NullPointerException. Treating two null ids as equal would be worse
     * still: two brand new sprints added to the same Set would collapse into
     * one, and only the first would ever be saved. So an unsaved object is
     * equal to nothing but itself, which is what the first line below covers.
     *
     * @return true when both objects carry the same non-null id, or when they
     *         are literally the same object.
     */
    @Override
    public boolean equals(Object o) {
        // The fast path, and the only rule that still holds for an object that
        // has never been saved: an object is always equal to itself, even
        // while its id is still null.
        if (this == o) return true;
        // "instanceof BaseEntity other" is pattern matching (Java 16 and
        // later; this project runs on Java 21). It checks the type and, when
        // the check passes, declares the already-cast variable "other" in one
        // step. Written with an old-style cast this would take three lines and
        // could throw ClassCastException. It also answers false for null, so
        // no separate null check is needed above.
        if (!(o instanceof BaseEntity other)) return false;
        return id != null && id.equals(other.id);
    }

    /**
     * Returns one and the same number for every instance of a given entity
     * class: every Permission gets one number, every Sprint another.
     *
     * <p>This looks wrong at first sight and it is deliberate. The rule of
     * Java is that an object placed in a HashSet, or used as the key of a
     * HashMap, must keep the SAME hash code for as long as it stays in there.
     *
     * <p>The obvious alternative, {@code return id.hashCode()}, breaks that
     * rule with JPA. A new sprint has a null id, which throws
     * NullPointerException; and guarding against null is not enough either.
     * Put the object into a Set while its id is still null, save it, and
     * Hibernate writes the id into the field. The hash code has just changed,
     * the object now sits in the wrong bucket of the Set, and
     * {@code set.contains(sprint)} answers false for an object the Set really
     * is holding.
     *
     * <p>{@code getClass().hashCode()} never changes, because the class of an
     * object never changes. The price is that every Permission falls into the
     * same bucket of a HashSet, so a lookup walks that bucket instead of
     * jumping straight to the element. That is accepted here because the sets
     * are tiny: Role.permissions holds about twenty rows.
     *
     * <p>getClass() rather than a hard-coded constant: this way a Permission
     * and a Sprint do not even share a bucket.
     */
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

package com.pms.mission.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One cost line of a business trip (per diem, ticket, stamp, transport, stay) — a row of table
 * "composantes_mission" (migration V10). A Mission holds no money itself; this entity is where
 * the "other costs" stream (kept separate from Cout Actuel = JH x TCC, see ARCHITECTURE.md 7.2)
 * gets its source data. Soft-deleted via the inherited "deleted" flag, like every entity here.
 */
/*
 * @Entity/@Table("composantes_mission") are required: Hibernate would otherwise guess
 * "composante_mission" and fail startup validation (ddl-auto: validate) against the real table.
 * Lombok generates accessors, the no-args constructor Hibernate needs, and a named-argument
 * builder so the two adjacent Strings (currency, description) can't be swapped by position.
 */
@Entity
@Table(name = "composantes_mission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComposanteMission extends BaseEntity {

    // Parent trip. fetch = LAZY avoids loading Mission (and its Project/User) on every list
    // read; the repository JOIN FETCHes it when actually needed (open-in-view is false, so a
    // late touch would throw LazyInitializationException). nullable = false: a cost line always
    // belongs to a mission (fk_comp_mission, V10), so it can never become orphaned and unreachable.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_id", nullable = false)
    private Mission mission;

    // Kind of cost — see TypeComposante. EnumType.STRING stores the word, not the ordinal
    // position, so inserting a new enum value later can't silently reinterpret old rows; it also
    // matches DB rule chk_comp_type (V10). @Column name is explicit for clarity even though the
    // default naming strategy would already produce it.
    @Enumerated(EnumType.STRING)
    @Column(name = "type_composante", nullable = false, length = 30)
    private TypeComposante typeComposante;

    // Amount in the currency below. BigDecimal, not double, to avoid cent drift when summing.
    // precision/scale = 15/2 maps to NUMERIC(15,2). The DB also rejects zero/negative values
    // (chk_comp_montant), mirrored by @Positive on the incoming DTO for a clean 400 instead.
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    // ISO currency code, upper-cased by ComposanteService before save. @Builder.Default keeps
    // the "TND" default when built via the builder; without it Lombok would ignore the
    // initializer and a builder call omitting .devise() would violate the NOT NULL column.
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String devise = "TND";

    // Free text detail, optional on purpose; callers must accept null. length = 500 matches
    // VARCHAR(500) in V10.
    @Column(length = 500)
    private String description;
}

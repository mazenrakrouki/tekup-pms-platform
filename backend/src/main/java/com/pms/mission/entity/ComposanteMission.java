package com.pms.mission.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * ComposanteMission = one cost line of a business trip: one per diem, one plane ticket, one
 * stamp/tax, one transport fee or one stay. It is one row of the table "composantes_mission"
 * created by migration V10. One Mission usually has several of them.
 *
 * Where it sits in the flow:
 *   MissionController (/api/projects/{projectId}/missions/{missionId}/composantes)
 *     -> ComposanteService (permission check, transaction, and the check that the mission
 *        really belongs to the project of the URL)
 *       -> ComposanteMissionRepository (findActiveByMissionId / findActiveById)
 *         -> this entity
 *           -> ComposanteMapper -> ComposanteResponse (the JSON sent to the Angular app).
 * Upwards it points to Mission through the "mission" field, and the Mission points to the
 * project - that chain is what makes an amount belong to a project.
 *
 * Why it exists: a Mission only says who travels, where and when; it holds no money at all.
 * Without this entity the application could record a trip but never what it costs, and the
 * "other costs" stream of a project (missions and frais, which docs/ARCHITECTURE.md 7.2
 * keeps separate from the labour cost Cout Actuel = JH x TCC) would have no source data.
 *
 * Soft delete: like every entity here it inherits the "deleted" flag from BaseEntity.
 * ComposanteService.delete only sets that flag, and the repository queries read rows where
 * deleted = false. A removed cost line stays in the database for audit but leaves the screen.
 */
/*
 * @Entity makes this class a table-backed object; without it the application would refuse to
 * start, because ComposanteMissionRepository would report "not a managed type".
 * @Table fixes the name to "composantes_mission": Hibernate would otherwise guess
 * "composante_mission" from the class name and, since application.yml sets ddl-auto to
 * "validate", the start-up check would fail because that table does not exist.
 * Lombok annotations: @Getter/@Setter generate the accessors used by the mapper and by
 * ComposanteService; @NoArgsConstructor is required by Hibernate, which creates an empty
 * object before filling it with the values read from the database; @AllArgsConstructor and
 * @Builder let the service write ComposanteMission.builder().montant(...).build() instead of
 * a five-argument constructor where the currency and the description - two Strings side by
 * side - could easily be passed in the wrong order.
 */
@Entity
@Table(name = "composantes_mission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComposanteMission extends BaseEntity {

    /*
     * The trip this cost line belongs to. @ManyToOne: many cost lines point to one mission.
     *
     * fetch = LAZY means the Mission row is not read together with this line; Hibernate puts
     * a stand-in in the field and queries the mission only if getMission() is called. Why:
     * listing the 6 cost lines of a trip would otherwise fire 6 extra SELECTs on missions,
     * plus their projects and users. When the mission is really needed - ComposanteService
     * compares composante.getMission().getId() with the mission id of the URL - the
     * repository brings it in the same query with JOIN FETCH c.mission. This matters because
     * open-in-view is false in application.yml: the Hibernate session is closed once the
     * controller returns, so a lazy field touched too late throws LazyInitializationException
     * instead of quietly running another query.
     *
     * @JoinColumn: the foreign key column is mission_id (constraint fk_comp_mission in V10)
     * and nullable = false. Without that, a line such as ("PERDIEM", 250) could be saved with
     * mission_id NULL: it would belong to no trip and no project, nobody could reach it
     * through the API, and it would still sit in the table for ever.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_id", nullable = false)
    private Mission mission;

    /*
     * Which kind of cost this line is - see TypeComposante for the five allowed values.
     *
     * @Enumerated(EnumType.STRING) stores the word ("BILLET"), not the position number.
     * Why: the JPA default is ORDINAL, which stores 0, 1, 2... If someone later inserted a
     * new value in the middle of the enum, every existing row would silently change meaning
     * and a stored plane ticket would be read back as a stamp. Storing the word also matches
     * the database rule chk_comp_type (V10), which only accepts those five texts.
     * Because the stored value is text, the ORDER BY c.typeComposante of
     * findActiveByMissionId sorts on the word itself (BILLET, PERDIEM, SEJOUR, TIMBRE,
     * TRANSPORT), not on the order the values are written in the enum.
     *
     * @Column(name = "type_composante") writes the SQL column name by hand. Spring Boot's
     * default naming strategy would already turn typeComposante into type_composante, so this
     * is not strictly required; it is written down so the mapping stays correct even if that
     * strategy is changed, and so the reader sees the real column name at once.
     * length = 30 matches VARCHAR(30) in V10.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type_composante", nullable = false, length = 30)
    private TypeComposante typeComposante;

    /*
     * The amount of this cost line, expressed in the currency of the field below.
     *
     * BigDecimal and never double: a double cannot hold 0.1 exactly, so adding per diems
     * would give totals such as 300.00000000000006 and the cents would drift away from the
     * accounting figures.
     * precision = 15, scale = 2 maps to NUMERIC(15,2): 13 digits before the decimal point and
     * exactly 2 after, so 1250.5 is stored as 1250.50.
     * nullable = false: a cost line without an amount would be meaningless. The database also
     * refuses zero and negative values (chk_comp_montant CHECK (montant > 0) in V10), and the
     * incoming DTO (data transfer object = the plain object that carries the JSON body, here
     * ComposanteRequest) repeats the same rule with @Positive, so a wrong input comes back as
     * a clean 400 with a readable message instead of reaching the database and blowing up as
     * a constraint violation.
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    /*
     * ISO currency code of the amount, three letters such as "TND" or "EUR".
     * ComposanteService stores it upper-cased, so "tnd" and "TND" never end up in the table
     * as two different currencies.
     *
     * @Builder.Default tells Lombok to keep the "TND" written on the next line when the
     * object is created through ComposanteMission.builder(). Without that single annotation
     * Lombok ignores the initial value, so a builder call that does not set .devise() would
     * produce devise = null and the INSERT would fail on the NOT NULL column - a bug that
     * only shows up at run time, never at compile time.
     */
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String devise = "TND";

    /*
     * Free text detail typed by the project manager ("2 nights in Tunis, hotel invoice 412").
     * Optional on purpose - there is no nullable = false - so a line can be saved with just a
     * type and an amount; any code reading this field must accept null.
     * length = 500 matches VARCHAR(500) in V10, and ddl-auto "validate" makes the application
     * refuse to start if the Java model and the real column ever drift apart.
     */
    @Column(length = 500)
    private String description;
}

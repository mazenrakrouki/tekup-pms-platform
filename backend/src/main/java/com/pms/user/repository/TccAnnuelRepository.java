package com.pms.user.repository;

import com.pms.user.entity.TccAnnuel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

/**
 * WHAT THIS FILE IS
 * Database access for one table: tcc_annuels. One row of that table says "for THIS
 * resource, in THIS year, the daily rate and the TCC rate are these". A repository is the
 * only place in the application that talks to the database for that table. It carries no
 * business rule and no permission check; both live in the services above it.
 *
 * WHY THE TABLE EXISTS AT ALL (spec F-AFF-13, section 6.3, rule 4)
 * TCC is the loading coefficient the company adds on top of a daily rate to get what a
 * man-day really costs, and it is renegotiated every year: the TCC of 2024 is not the TCC
 * of 2025. A project that runs over three years must therefore cost a day worked in 2024
 * at the 2024 rate. resources.daily_rate / resources.tcc_rate hold the CURRENT rate of a
 * person; this table holds the per-year overrides. The rule is: the row of the year the
 * day was charged to wins; when there is no row for that year, the base rate of the
 * resource applies.
 *   WITHOUT this table: every past day would silently be re-priced at today's rate. On a
 *   multi-year project that moves the margin by several points, and the figure could
 *   never be reconciled with the accounting - which is exactly the kind of number a jury
 *   will ask you to justify.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> ResourceController (/api/resources/{id}/tcc, GET and PUT)
 *           -> ResourceService    (@PreAuthorize VIEW_RESOURCES to read,
 *                                  MANAGE_RESOURCES to write)
 *           -> TccAnnuelRepository (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table tcc_annuels
 * The TccAnnuel rows never leave the server: ResourceService copies three fields into the
 * TccAnnuelDto record, and that record is what becomes JSON.
 * The second caller is KpiService, and it is the one that matters for the figures: it
 * loads every yearly rate of every person of a project in one query and builds the map
 * userId -> (year -> rate) that prices each charged day.
 *
 * HOW THIS FILE RELATES TO ResourceRepository, NEXT TO IT
 * One repository per table is the rule of this code base. ResourceRepository reads the
 * parent table (the person and his base rate); this one reads the child table (his rates
 * per year). KpiService uses both, in that order, and applies the "year rate first, base
 * rate otherwise" rule between them. Note that a DI (Devis Interne - internal quote) or a
 * KPI amount is never stored in a column: it is recomputed from these rates every time it
 * is read, so correcting a rate here immediately corrects every figure derived from it.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * The per-year TCC screen would disappear, and KpiService would have no way to apply rule
 * 4 of F-AFF-13: every cost, margin and EVM indicator would fall back to the current rate
 * for all years.
 *
 * SECURITY - the protection that is NOT written in this file
 * @PreAuthorize sits on the SERVICE methods of ResourceService, never on the controller
 * and never here, and the project manager's data scope (ADR-021) is applied there too -
 * ResourceService.findTccAnnuels calls assertVisible(resourceId), which goes through
 * ResourceRepository.isResourceVisibleToProjectManager. Nothing of that is in this file,
 * so a method below called from a new place would hand out the rate history of anybody.
 */
// Nothing implements this interface by hand, and that is normal: at start-up Spring Data
// JPA reads the interfaces that extend JpaRepository, turns each @Query text below into a
// real SQL statement, and builds the implementation itself (a "proxy" object) which it
// hands to the services that asked for a TccAnnuelRepository. No @Repository annotation
// is needed, because extending JpaRepository is already the signal Spring looks for.
// The two types between < > are generics: TccAnnuel = the entity, so the table read is
// tcc_annuels; Long = the type of the @Id field (inherited from BaseEntity), so findById
// takes a Long. Without them the methods would return Object and every caller would need
// a cast, with a ClassCastException waiting at run time.
// JpaRepository also brings in saveAll(), which ResourceService uses twice in
// replaceTccAnnuels - once for the years it keeps or adds, once for the years it soft-
// deletes. It never calls a delete method: removing a year means setting deleted = true,
// so a past KPI figure can still be explained.
public interface TccAnnuelRepository extends JpaRepository<TccAnnuel, Long> {

    // WHAT: every live yearly rate of ONE resource, oldest year first.
    // WHY "t.resource.id = :resourceId" and not a join: the id of a to-one link is
    //       already in the tcc_annuels table, in the foreign key column resource_id, so
    //       Hibernate reads it without touching the resources table at all. Comparing
    //       something like t.resource.user.id would have forced two real joins for a
    //       value this row already holds.
    // WHY "t.deleted = false": soft delete. ResourceService never erases a year the user
    //       removes from the grid, it flags it. Without this filter a rate the
    //       administrator deleted last month would come back on screen - and, worse, the
    //       "which years already exist?" map built in replaceTccAnnuels would contain it,
    //       so saving the grid would try to revive it.
    // WHY "ORDER BY t.annee": the screen shows one line per year and people read years in
    //       order.
    //       WITHOUT any ORDER BY: PostgreSQL returns the rows in no guaranteed order and
    //       the grid could show 2025 above 2023 after a reload.
    // WHY there is NO JOIN FETCH here, unlike the method below: the caller already holds
    //       the Resource - ResourceService loaded it just before - and only reads annee,
    //       dailyRate and tccRate, which are plain columns of this table. Fetching the
    //       resource again would be a join for data nobody uses.
    // SPEED: V21__tcc_annuel.sql creates "CREATE INDEX idx_tcc_annuel_resource ON
    //       tcc_annuels(resource_id) WHERE deleted = FALSE", which matches both
    //       conditions of this query exactly.
    //
    // WHO CALLS IT: ResourceService twice - findTccAnnuels (the GET, after the scope
    // check) and replaceTccAnnuels (the PUT), where the result is turned into a
    // year -> row map so that an existing year is UPDATED in place instead of being
    // deleted and re-inserted. That detail is not a style choice: the partial unique
    // index uk_tcc_annuel_resource_annee ON tcc_annuels(resource_id, annee) WHERE
    // deleted = FALSE (V21) refuses two live rows for the same year, and Hibernate
    // performs its INSERTs before its UPDATEs when it flushes - so the delete-then-insert
    // version made every edit of an existing year fail with a duplicate-key error (HTTP
    // 409) while adding a brand new year worked.
    @Query("SELECT t FROM TccAnnuel t WHERE t.resource.id = :resourceId AND t.deleted = false ORDER BY t.annee")
    List<TccAnnuel> findActiveByResourceId(Long resourceId);

    // WHAT: every live yearly rate of MANY people at once, found from the USER ids rather
    //       than from the resource ids, with the resource and the person behind it
    //       already loaded.
    // WHY IT EXISTS - it is a performance rule. KpiService prices a project by walking
    //       every planned month and every declared month of every member; a project with
    //       10 people over 12 months is around 240 rows. Asking for the yearly rates one
    //       person at a time would be one round trip per person on every KPI screen. Here
    //       it is exactly ONE query, and KpiService turns the result into the nested map
    //       userId -> (year -> rate), which answers "this person, that year" in constant
    //       time.
    // WHY it starts from USER ids and not resource ids: KpiService works from plan and
    //       timesheet rows, and those rows point at a USER, never at a Resource. Making
    //       the caller translate user ids into resource ids first would mean a second
    //       query for nothing.
    //
    // "r.user.id IN :userIds" - one parameter holding the whole collection; Hibernate
    //       expands it into "IN (?, ?, ?...)".
    //       WATCH OUT: an empty collection would produce "IN ()", which is not valid SQL.
    //       That is why KpiService guards the call with "if (!userIds.isEmpty())" - a
    //       brand new project with no plan and no timesheet takes that branch every time.
    // "JOIN FETCH t.resource r JOIN FETCH r.user" - both links are mapped LAZY
    //       (TccAnnuel.resource is @ManyToOne(LAZY), Resource.user is @OneToOne(LAZY)),
    //       and KpiService keys its map on t.getResource().getUser().getId(). Fetching
    //       them here brings the whole chain back in ONE statement.
    //       WITHOUT the fetch: two extra SELECTs per row, and - because application.yml
    //       sets "open-in-view: false" - a LazyInitializationException as soon as the
    //       chain is read outside the loading transaction.
    // WHY plain JOINs and not LEFT: resource_id is NOT NULL on tcc_annuels (V21) and
    //       user_id is NOT NULL on resources (V3), so no row can be lost by the inner
    //       joins.
    //
    // "r.deleted = false" IS NOT OPTIONAL, AND THIS ONE COST A REAL BUG. Without it, the
    // yearly rate of an ARCHIVED resource kept feeding the cost calculation, while
    // ResourceRepository - which filters on r.deleted = false everywhere - had already
    // dropped that person. A resource that had been deleted and created again therefore
    // saw its OLD rate win over the new one, and the two screens disagreed on the cost of
    // the same project.
    // "t.deleted = false" is the second, different question: a single YEAR that was
    // removed from the grid must not come back, even on a resource that is perfectly
    // live.
    //
    // WHY NO ORDER BY here, unlike the method above: the caller does not display this
    // list, it indexes it into a map. A sort would be work thrown away.
    //
    // WHO CALLS IT: KpiService, when it builds the per-year rate map used to cost a
    // project (F-AFF-13 section 6.3 rule 4).
    @Query("SELECT t FROM TccAnnuel t JOIN FETCH t.resource r JOIN FETCH r.user WHERE r.user.id IN :userIds AND r.deleted = false AND t.deleted = false")
    List<TccAnnuel> findActiveByUserIdIn(Collection<Long> userIds);
}

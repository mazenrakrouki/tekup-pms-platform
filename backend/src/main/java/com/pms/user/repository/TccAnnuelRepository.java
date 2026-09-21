package com.pms.user.repository;

import com.pms.user.entity.TccAnnuel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

/**
 * Database access for tcc_annuels — per-resource, per-year overrides of the daily/TCC rate
 * (spec F-AFF-13 §6.3 rule 4: TCC is renegotiated yearly, so a day charged in 2024 must
 * cost at the 2024 rate even when read today). resources.daily_rate/tcc_rate hold the
 * current rate; a row here overrides it for the year charged, falling back to the base
 * rate when no row exists for that year. Without this table every past day would silently
 * re-price at today's rate.
 *
 * <p>Read/written through ResourceService (VIEW_RESOURCES to read, MANAGE_RESOURCES to
 * write, at /api/resources/{id}/tcc); also read by KpiService, which loads every yearly
 * rate of every project member in one query to build the userId -> (year -> rate) map used
 * to price charged days. Sits next to ResourceRepository (parent table: the person and
 * base rate) — KpiService uses both, year rate first, base rate otherwise.
 *
 * <p>Security note: @PreAuthorize and the ADR-021 project-manager scope
 * (ResourceService.assertVisible -> ResourceRepository.isResourceVisibleToProjectManager)
 * both live on ResourceService, never here.
 */
public interface TccAnnuelRepository extends JpaRepository<TccAnnuel, Long> {

    // Every live yearly rate of one resource, oldest year first. No JOIN FETCH: the caller
    // (ResourceService) already holds the Resource and only reads annee/dailyRate/tccRate.
    // Used by findTccAnnuels (GET) and replaceTccAnnuels (PUT), which turns the result into
    // a year->row map so an existing year is UPDATED in place. That matters because
    // uk_tcc_annuel_resource_annee (V21, partial unique) rejects two live rows for the same
    // year, and Hibernate flushes INSERTs before UPDATEs — a delete-then-reinsert approach
    // made editing an existing year fail with a duplicate-key 409.
    @Query("SELECT t FROM TccAnnuel t WHERE t.resource.id = :resourceId AND t.deleted = false ORDER BY t.annee")
    List<TccAnnuel> findActiveByResourceId(Long resourceId);

    // Batched version for KpiService: one query for every member's yearly rates instead of
    // one round trip per person (~240 rows/project). Starts from USER ids because plan and
    // timesheet rows point at a user, never a Resource. JOIN FETCH brings resource+user back
    // in one statement (both links are LAZY; KpiService keys its map on
    // t.getResource().getUser().getId()). Empty userIds would produce invalid "IN ()" SQL,
    // so KpiService guards with isEmpty() first.
    // r.deleted=false is NOT optional and once caused a real bug: without it, an archived
    // resource's old yearly rate kept feeding costs after ResourceRepository had already
    // dropped that person elsewhere. t.deleted=false is the separate "this year was removed
    // from the grid" question.
    @Query("SELECT t FROM TccAnnuel t JOIN FETCH t.resource r JOIN FETCH r.user WHERE r.user.id IN :userIds AND r.deleted = false AND t.deleted = false")
    List<TccAnnuel> findActiveByUserIdIn(Collection<Long> userIds);

    // Batched for the resources list/detail screens: one query for every row's current-year
    // override instead of one per resource. Same "year rate first, base rate otherwise" rule
    // as KpiService, just for "today" instead of a charge's own year — ResourceService uses
    // this to show each resource's actually-effective rate rather than the base rate, which
    // is only the fallback for years with no override (this year included).
    @Query("SELECT t FROM TccAnnuel t WHERE t.resource.id IN :resourceIds AND t.annee = :annee AND t.deleted = false")
    List<TccAnnuel> findActiveByResourceIdInAndAnnee(Collection<Long> resourceIds, Integer annee);
}

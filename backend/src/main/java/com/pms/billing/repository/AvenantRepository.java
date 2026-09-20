package com.pms.billing.repository;

// The only place in the application that reads the "avenants" table (Flyway V9, column
// workload_days added by V15). AvenantService holds the permission check
// (hasAuthority('VIEW_BILLING')/'MANAGE_BILLING'), and ProjectScopeInterceptor (ADR-021) scopes
// every /api/projects/{id}/** URL to what the caller may touch — this repository just fetches.
//
// One rule runs through the whole file: soft delete. AvenantService.delete() only flips
// BaseEntity's "deleted" flag, never removes a row, so money history stays auditable. That means
// every query here must filter deleted=false itself — the inherited findAll/findById/count don't
// know about the flag and would happily return a cancelled amendment, double-counting its amount.

import com.pms.billing.entity.Avenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Read/write access to the Avenant entity. No implementation is written here: Spring Data JPA
 * generates one from the JpaRepository&lt;Avenant, Long&gt; generics at startup, giving us
 * save/findById/count for free and catching a bad field name in a query below at boot time
 * rather than months later in a raw SQL string.
 */
public interface AvenantRepository extends JpaRepository<Avenant, Long> {

    /*
     * JOIN FETCH a.project: loads the parent Project in the same query since Avenant.project is
     * LAZY — without it, listing 20 amendments would fire 20 extra SELECTs just to read
     * project.code in AvenantMapper (classic N+1). :projectId binds by argument name (works
     * without @Param because Spring Boot compiles with -parameters); deleted=false hides
     * soft-deleted rows; ORDER BY dateAvenant keeps repeated page loads in a stable order.
     */
    /**
     * Every amendment still alive for one project, oldest signature date first, with the parent
     * Project already loaded. Called by AvenantService.findByProject() for the billing screen.
     */
    @Query("SELECT a FROM Avenant a JOIN FETCH a.project WHERE a.project.id = :projectId AND a.deleted = false ORDER BY a.dateAvenant")
    List<Avenant> findActiveByProjectId(Long projectId);

    /*
     * Same JOIN FETCH + deleted=false as above, for a single row. Filters on the amendment id
     * only, not the project — AvenantService.delete() re-checks avenant.getProject().getId()
     * against the URL's projectId itself, so a user scoped to project A can't delete an
     * amendment of project B by guessing its id even if they knew it.
     */
    /**
     * One amendment by id, only if not soft-deleted. Called by AvenantService.delete() before it
     * flags deleted=true and subtracts the amount back out of the project's revised budget.
     */
    @Query("SELECT a FROM Avenant a JOIN FETCH a.project WHERE a.id = :id AND a.deleted = false")
    Optional<Avenant> findActiveById(Long id);
}

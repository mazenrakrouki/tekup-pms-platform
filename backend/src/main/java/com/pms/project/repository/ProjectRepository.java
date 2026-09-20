package com.pms.project.repository;

import com.pms.project.entity.Project;
import com.pms.project.entity.ProjectStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Database access for the projects table, the central table of the app. No business rule or
// permission check lives here — those sit in ProjectService above it (VIEW_PROJECT,
// CREATE_PROJECT, EDIT_PROJECT..., plus BR-050 financial masking). Two flags matter throughout:
// "deleted" is a soft delete, filtered out everywhere; "archived" (V16) is a finished project set
// aside but still fully readable — only the active-list methods exclude it, not findActiveById.
// The ADR-021 scope check (a project id in the URL) can't apply to the list endpoints below,
// which carry no id — those are filtered in ProjectService using findAccessibleProjectIdsByEmail.
public interface ProjectRepository extends JpaRepository<Project, Long> {

    // Every project that is neither deleted nor archived, sorted by code, with director/chefProjet
    // already loaded via LEFT JOIN FETCH (LEFT because both links are nullable) to avoid N+1
    // queries — ProjectMapper reads both users' id and full name on every row. Returns the whole
    // portfolio with no scope filter; ProjectService.findAll() applies accessibleProjectIds()
    // afterwards unless the caller holds VIEW_ALL_PROJECTS. Called by findAll() and AgileDemoSeeder.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false ORDER BY p.code")
    List<Project> findAllActive();

    // Mirror of findAllActive() for archived, non-deleted projects — kept as a separate method
    // (not a boolean parameter) so the "which list" choice can't be passed in wrong by a caller.
    // Called only by ProjectService.findArchived().
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = true ORDER BY p.code")
    List<Project> findAllArchived();

    // Reads one live project by id, with both users loaded. Optional forces every caller to
    // handle "not found" (typically .orElseThrow(NotFoundException)) instead of risking a null
    // deref. Deliberately ignores "archived" so an archived project stays openable and can be
    // unarchived. The busiest method in this repository: used by fifteen services' loadProject().
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.id = :id AND p.deleted = false")
    Optional<Project> findActiveById(Long id);

    // Every live project managed by a given user id (chef de projet). No caller today; the
    // scope check actually used, findAccessibleProjectIdsByEmail below, covers more (chef OR
    // team member) and keys off email, since that's what the JWT carries.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.chefProjet.id = :userId AND p.deleted = false")
    List<Project> findActiveByChefProjetId(Long userId);

    // Whether a live project already uses this code — a derived query (method name -> SQL),
    // checked against the entity at startup. Not the real protection: ProjectService.create()
    // calls this then saves, leaving a race window that only the partial unique index
    // uk_projects_code (WHERE deleted = FALSE, V18) actually closes; this method exists purely
    // to give the normal case a clean 409 instead of a raw DB error. Called by
    // ProjectService.create/update and the demo seeders (as their "already seeded?" check).
    boolean existsByCodeAndDeletedFalse(String code);

    // Reads one live project by its business code rather than its id. No JOIN FETCH here, unlike
    // findActiveById, so director/chefProjet stay lazy for callers that don't need them. No
    // caller today; the natural entry point for anything that knows a project by its contract code.
    Optional<Project> findByCodeAndDeletedFalse(String code);

    // Every live project in a given ProjectStatus, both users loaded. Typed as the enum so an
    // invalid value can't silently return an empty list. Doesn't filter on archived. No caller
    // today; partial index idx_projects_status (V5) backs this query.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.status = :status AND p.deleted = false")
    List<Project> findActiveByStatus(ProjectStatus status);

    // Paged version of findAllActive(), used once the portfolio grows too large for one response
    // (Angular's list asks for 20 rows at a time). countQuery is supplied explicitly rather than
    // left to Spring Data's automatic rewrite, because a JOIN FETCH inside an auto-generated
    // COUNT query fails ("owner of the fetched association was not present in the select list").
    // Both fetches are @ManyToOne so LIMIT/OFFSET paging stays safe — a fetched collection would
    // force in-memory paging instead. Sort comes from the Pageable's default (code, ASC) declared
    // on the controller. Called by ProjectService.findAll() for a caller with VIEW_ALL_PROJECTS.
    @Query(value = "SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false",
           countQuery = "SELECT COUNT(p) FROM Project p WHERE p.deleted = false AND p.archived = false")
    Page<Project> findAllActivePaged(Pageable pageable);

    // Same paged query, restricted to a given set of ids (ADR-021 scope, from
    // findAccessibleProjectIdsByEmail below) — filtered in SQL rather than in Java so the row
    // count and the page total stay consistent with what the caller may see. An empty id
    // collection would produce invalid "IN ()" SQL; ProjectService guards against that before
    // calling this method. Called by ProjectService.findAll() for callers without
    // VIEW_ALL_PROJECTS.
    @Query(value = "SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false AND p.id IN :ids",
           countQuery = "SELECT COUNT(p) FROM Project p WHERE p.deleted = false AND p.archived = false AND p.id IN :ids")
    Page<Project> findAllActiveByIdIn(@Param("ids") Collection<Long> ids, Pageable pageable);

    /**
     * M-6: ids of every active, non-archived project where the caller is either the chef de
     * projet or an active (non-deleted) team member — one query replacing three separate
     * lookups the scope service used to make. Runs on every request under
     * /api/projects/{id}/** via ProjectScopeInterceptor, so the consolidation matters on the
     * busiest path of the app. Built from relations, never a role name (ADR-001): a holder of
     * VIEW_ALL_PROJECTS bypasses this query entirely via ProjectScopeService.hasAllAccess().
     * Matches on chefProjet's email (LEFT join, since chef_projet_id is nullable) rather than a
     * user id, because that's what the JWT subject carries. Team membership uses an EXISTS
     * subquery rather than a second join, so a project with several team members isn't repeated
     * per member. Returns Set<Long>, since the caller only ever asks "contains(id)?".
     * Called by ProjectScopeService.accessibleProjectIds(email).
     */
    @Query("SELECT DISTINCT p.id FROM Project p " +
           "LEFT JOIN p.chefProjet cp " +
           "WHERE p.deleted = false AND p.archived = false " +
           "AND (cp.email = :email " +
           "OR EXISTS (SELECT ta FROM TeamAssignment ta " +
           "           WHERE ta.project = p AND ta.user.email = :email AND ta.deleted = false))")
    Set<Long> findAccessibleProjectIdsByEmail(@Param("email") String email);
}

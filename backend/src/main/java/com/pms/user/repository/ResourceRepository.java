package com.pms.user.repository;

import com.pms.user.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for the resources table — the MONEY side of a person: daily rate, TCC
 * rate (loading coefficient; real cost of a day = daily_rate x (1 + tcc_rate)), and
 * staffing dates. Read/written through ResourceService (@PreAuthorize VIEW_RESOURCES /
 * MANAGE_RESOURCES); also read by KpiService.findActiveByUserIdIn to price a whole
 * project's charged days at once.
 *
 * <p>Kept as a separate table from users (ADR-022): an account can exist without a
 * billable rate, and a rate must survive after the account is switched off. The link is
 * one-to-one and mandatory (resources.user_id NOT NULL), so every query here can inner-join
 * the user safely.
 *
 * <p>ADR-021's ProjectScopeInterceptor only guards {@code /api/projects/{id}/**}, but
 * {@code /api/resources/{id}} carries a resource id with no project id to check — so the
 * project-manager data scope is hand-written here as two queries:
 * findVisibleToProjectManager (the list) and isResourceVisibleToProjectManager (the
 * single-row test). Both are applied only when the caller lacks MANAGE_RESOURCES, and both
 * take the manager's own id from the authenticated token, never from a request parameter.
 *
 * <p>Security note: the @PreAuthorize checks live on ResourceService, never here — a
 * method of this file called directly would skip both the permission and the scope.
 */
public interface ResourceRepository extends JpaRepository<Resource, Long> {

    // Whole live TCC referential, person pre-loaded, sorted by last name. JOIN FETCH avoids
    // N+1 selects (ResourceMapper reads user.getFullName() for every row) and the
    // LazyInitializationException open-in-view:false would otherwise cause. Plain JOIN, not
    // LEFT, because user_id is NOT NULL.
    // CAREFUL: this is the whole referential. ResourceService only calls it for callers
    // holding MANAGE_RESOURCES; a project manager is sent to findVisibleToProjectManager.
    @Query("SELECT r FROM Resource r JOIN FETCH r.user u WHERE r.deleted = false ORDER BY u.lastName")
    List<Resource> findAllActive();

    // Live resource of one user account, used as a yes/no presence test by
    // ResourceService.create ("does this person already have a rate?"). uk_resources_user_id
    // (V4) is an ABSOLUTE unique constraint (not partial like users.email), so a
    // soft-deleted resource still occupies its user_id — this method's r.deleted=false
    // filter is what lets that user look free again.
    @Query("SELECT r FROM Resource r JOIN FETCH r.user WHERE r.user.id = :userId AND r.deleted = false")
    Optional<Resource> findActiveByUserId(Long userId);

    // Same read, batched for many users: KpiService prices ~240 rows per project and needs
    // one round trip, not one per person. Empty collection would produce invalid "IN ()"
    // SQL, so KpiService guards with userIds.isEmpty() before calling.
    @Query("SELECT r FROM Resource r JOIN FETCH r.user WHERE r.user.id IN :userIds AND r.deleted = false")
    List<Resource> findActiveByUserIdIn(java.util.Collection<Long> userIds);

    // The TCC rows a project manager may see (ADR-021 scope): people on projects he
    // manages, plus himself. Three nested levels: projects he manages (p.chefProjet.id) ->
    // people assigned to those projects (TeamAssignment) -> resources of those people OR of
    // the manager himself (he may not be staffed on his own project).
    // The three "deleted = false" guard different things — resource, team assignment
    // (leaving a team is a soft delete) and project — dropping any one leaks stale data.
    // DISTINCT is a safety net against a future rewrite of the sub-selects into joins.
    @Query("""
            SELECT DISTINCT r FROM Resource r JOIN FETCH r.user u
            WHERE r.deleted = false AND (
                u.id = :pmUserId
                OR u.id IN (
                    SELECT ta.user.id FROM TeamAssignment ta
                    WHERE ta.deleted = false AND ta.project.id IN (
                        SELECT p.id FROM Project p
                        WHERE p.deleted = false AND p.chefProjet.id = :pmUserId
                    )
                )
            )
            ORDER BY u.lastName
            """)
    List<Resource> findVisibleToProjectManager(Long pmUserId);

    // Single-row twin of the list above (same three levels), used by
    // ResourceService.assertVisible so a GET on one id doesn't have to load and scan the
    // manager's whole perimeter. The two queries encode the same rule twice and must be
    // kept in step, or a resource could be listed but 403 when opened, or vice versa.
    // COUNT(...)>0 rather than selecting the row: an aggregate always returns exactly one
    // row, so unboxing to a plain boolean never NPEs when nothing matches.
    @Query("""
            SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Resource r
            WHERE r.id = :resourceId AND r.deleted = false AND (
                r.user.id = :pmUserId
                OR r.user.id IN (
                    SELECT ta.user.id FROM TeamAssignment ta
                    WHERE ta.deleted = false AND ta.project.id IN (
                        SELECT p.id FROM Project p
                        WHERE p.deleted = false AND p.chefProjet.id = :pmUserId
                    )
                )
            )
            """)
    boolean isResourceVisibleToProjectManager(Long resourceId, Long pmUserId);
}

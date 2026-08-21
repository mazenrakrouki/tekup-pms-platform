package com.pms.user.repository;

import com.pms.user.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    @Query("SELECT r FROM Resource r JOIN FETCH r.user u WHERE r.deleted = false ORDER BY u.lastName")
    List<Resource> findAllActive();

    @Query("SELECT r FROM Resource r JOIN FETCH r.user WHERE r.user.id = :userId AND r.deleted = false")
    Optional<Resource> findActiveByUserId(Long userId);

    @Query("SELECT r FROM Resource r JOIN FETCH r.user WHERE r.user.id IN :userIds AND r.deleted = false")
    List<Resource> findActiveByUserIdIn(java.util.Collection<Long> userIds);

    /**
     * Ressources visibles par un chef de projet : celles dont l'utilisateur est membre actif
     * d'une équipe d'un projet qu'il gère, ou lui-même. Scope de données PM (ADR-021) sur le TCC.
     */
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

    /** Vrai si la ressource est dans le périmètre TCC du chef de projet donné. */
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

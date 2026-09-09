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

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false ORDER BY p.code")
    List<Project> findAllActive();

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = true ORDER BY p.code")
    List<Project> findAllArchived();

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.id = :id AND p.deleted = false")
    Optional<Project> findActiveById(Long id);

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.chefProjet.id = :userId AND p.deleted = false")
    List<Project> findActiveByChefProjetId(Long userId);

    boolean existsByCodeAndDeletedFalse(String code);

    Optional<Project> findByCodeAndDeletedFalse(String code);

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.status = :status AND p.deleted = false")
    List<Project> findActiveByStatus(ProjectStatus status);

    @Query(value = "SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false",
           countQuery = "SELECT COUNT(p) FROM Project p WHERE p.deleted = false AND p.archived = false")
    Page<Project> findAllActivePaged(Pageable pageable);

    @Query(value = "SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false AND p.id IN :ids",
           countQuery = "SELECT COUNT(p) FROM Project p WHERE p.deleted = false AND p.archived = false AND p.id IN :ids")
    Page<Project> findAllActiveByIdIn(@Param("ids") Collection<Long> ids, Pageable pageable);

    /**
     * M-6 : requête unique remplaçant les 3 appels séparés de ProjectScopeService
     * (user lookup + chef-projects + team-assignments).
     * Retourne les IDs de tous les projets actifs dont l'utilisateur est chef de projet
     * OU membre actif d'équipe.
     */
    @Query("SELECT DISTINCT p.id FROM Project p " +
           "LEFT JOIN p.chefProjet cp " +
           "WHERE p.deleted = false AND p.archived = false " +
           "AND (cp.email = :email " +
           "OR EXISTS (SELECT ta FROM TeamAssignment ta " +
           "           WHERE ta.project = p AND ta.user.email = :email AND ta.deleted = false))")
    Set<Long> findAccessibleProjectIdsByEmail(@Param("email") String email);
}

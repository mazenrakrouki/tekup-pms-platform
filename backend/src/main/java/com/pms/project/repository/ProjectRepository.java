package com.pms.project.repository;

import com.pms.project.entity.Project;
import com.pms.project.entity.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false ORDER BY p.code")
    List<Project> findAllActive();

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = true ORDER BY p.code")
    List<Project> findAllArchived();

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.id = :id AND p.deleted = false")
    Optional<Project> findActiveById(Long id);

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.chefProjet.id = :userId AND p.deleted = false")
    List<Project> findActiveByChefProjetId(Long userId);

    boolean existsByCode(String code);

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.status = :status AND p.deleted = false")
    List<Project> findActiveByStatus(ProjectStatus status);
}

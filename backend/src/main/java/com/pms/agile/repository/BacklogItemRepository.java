package com.pms.agile.repository;

import com.pms.agile.entity.BacklogItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BacklogItemRepository extends JpaRepository<BacklogItem, Long> {

    // LEFT JOIN FETCH sur le sprint : la relation est nullable (élément du backlog produit,
    // non engagé). Un JOIN FETCH simple écarterait silencieusement ces éléments.
    @Query("""
           SELECT b FROM BacklogItem b
           JOIN FETCH b.project
           LEFT JOIN FETCH b.sprint
           WHERE b.project.id = :projectId AND b.deleted = false
           ORDER BY b.id ASC
           """)
    List<BacklogItem> findActiveByProjectId(Long projectId);

    @Query("""
           SELECT b FROM BacklogItem b
           JOIN FETCH b.project
           LEFT JOIN FETCH b.sprint
           WHERE b.id = :id AND b.deleted = false
           """)
    Optional<BacklogItem> findActiveById(Long id);

    /**
     * Détache les éléments d'un sprint supprimé : ils retournent au backlog produit plutôt
     * que de disparaître avec l'itération. Sans cela, supprimer un sprint ferait perdre de
     * vue du travail encore à faire.
     */
    // flushAutomatically : les écritures en attente partent avant le UPDATE en masse.
    // clearAutomatically : le contexte de persistance est vidé après, sinon les éléments
    // déjà chargés conserveraient leur ancienne référence de sprint et une lecture dans la
    // même transaction renverrait un sprint pourtant détaché.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BacklogItem b SET b.sprint = null WHERE b.sprint.id = :sprintId")
    void detachFromSprint(Long sprintId);
}

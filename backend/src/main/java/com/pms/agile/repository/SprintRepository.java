package com.pms.agile.repository;

import com.pms.agile.entity.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SprintRepository extends JpaRepository<Sprint, Long> {

    // Tri chronologique : la date de début porte l'ordre réel des itérations. Un tri sur
    // `status` classerait ACTIVE avant PLANNED alphabétiquement, ce qui n'a aucun sens ici.
    // NULLS LAST pour qu'un sprint non daté n'ouvre pas la liste.
    @Query("""
           SELECT s FROM Sprint s JOIN FETCH s.project
           WHERE s.project.id = :projectId AND s.deleted = false
           ORDER BY s.startDate ASC NULLS LAST, s.id ASC
           """)
    List<Sprint> findActiveByProjectId(Long projectId);

    @Query("SELECT s FROM Sprint s JOIN FETCH s.project WHERE s.id = :id AND s.deleted = false")
    Optional<Sprint> findActiveById(Long id);
}

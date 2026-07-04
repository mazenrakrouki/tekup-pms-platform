package com.pms.governance.repository;

import com.pms.governance.entity.Livrable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LivrableRepository extends JpaRepository<Livrable, Long> {

    @Query("SELECT l FROM Livrable l JOIN FETCH l.project WHERE l.project.id = :projectId AND l.deleted = false ORDER BY l.dateEcheance NULLS LAST, l.titre")
    List<Livrable> findActiveByProjectId(Long projectId);

    @Query("SELECT l FROM Livrable l JOIN FETCH l.project WHERE l.id = :id AND l.deleted = false")
    Optional<Livrable> findActiveById(Long id);
}

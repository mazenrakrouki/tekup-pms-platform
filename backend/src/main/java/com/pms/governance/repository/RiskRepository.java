package com.pms.governance.repository;

import com.pms.governance.entity.Risk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RiskRepository extends JpaRepository<Risk, Long> {

    // Tri par date de création (le plus récent d'abord). Les enums étant stockés
    // en STRING, un ORDER BY sur statut/impact trierait alphabétiquement et non par
    // gravité — un tri par sévérité nécessiterait une expression CASE applicative.
    @Query("SELECT r FROM Risk r JOIN FETCH r.project WHERE r.project.id = :projectId AND r.deleted = false ORDER BY r.createdAt DESC")
    List<Risk> findActiveByProjectId(Long projectId);

    @Query("SELECT r FROM Risk r JOIN FETCH r.project WHERE r.id = :id AND r.deleted = false")
    Optional<Risk> findActiveById(Long id);
}

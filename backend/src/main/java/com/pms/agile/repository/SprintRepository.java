package com.pms.agile.repository;

import com.pms.agile.entity.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SprintRepository extends JpaRepository<Sprint, Long> {

    // Chronological order: a board is read left to right in time, not by creation date.
    @Query("SELECT s FROM Sprint s JOIN FETCH s.project WHERE s.project.id = :projectId AND s.deleted = false ORDER BY s.startDate ASC")
    List<Sprint> findActiveByProjectId(Long projectId);

    @Query("SELECT s FROM Sprint s JOIN FETCH s.project WHERE s.id = :id AND s.deleted = false")
    Optional<Sprint> findActiveById(Long id);
}

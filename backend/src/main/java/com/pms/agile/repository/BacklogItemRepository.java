package com.pms.agile.repository;

import com.pms.agile.entity.BacklogItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BacklogItemRepository extends JpaRepository<BacklogItem, Long> {

    // LEFT JOIN on sprint: an item with no sprint belongs to the product backlog
    // and must still be returned. An inner join would silently hide it.
    @Query("SELECT b FROM BacklogItem b JOIN FETCH b.project LEFT JOIN FETCH b.sprint "
         + "WHERE b.project.id = :projectId AND b.deleted = false ORDER BY b.id ASC")
    List<BacklogItem> findActiveByProjectId(Long projectId);

    @Query("SELECT b FROM BacklogItem b JOIN FETCH b.project LEFT JOIN FETCH b.sprint "
         + "WHERE b.id = :id AND b.deleted = false")
    Optional<BacklogItem> findActiveById(Long id);

    @Query("SELECT b FROM BacklogItem b WHERE b.sprint.id = :sprintId AND b.deleted = false")
    List<BacklogItem> findActiveBySprintId(Long sprintId);
}

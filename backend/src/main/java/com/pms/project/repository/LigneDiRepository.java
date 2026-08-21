package com.pms.project.repository;

import com.pms.project.entity.LigneDi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LigneDiRepository extends JpaRepository<LigneDi, Long> {

    @Query("SELECT l FROM LigneDi l WHERE l.project.id = :projectId AND l.deleted = false ORDER BY l.section, l.ordre, l.id")
    List<LigneDi> findActiveByProjectId(Long projectId);

    @Query("SELECT COUNT(l) > 0 FROM LigneDi l WHERE l.project.id = :projectId AND l.deleted = false")
    boolean existsActiveByProjectId(Long projectId);
}

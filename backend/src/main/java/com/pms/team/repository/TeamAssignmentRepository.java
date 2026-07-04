package com.pms.team.repository;

import com.pms.team.entity.TeamAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TeamAssignmentRepository extends JpaRepository<TeamAssignment, Long> {

    @Query("SELECT ta FROM TeamAssignment ta JOIN FETCH ta.project JOIN FETCH ta.user WHERE ta.id = :id AND ta.deleted = false")
    Optional<TeamAssignment> findActiveById(Long id);

    @Query("SELECT ta FROM TeamAssignment ta JOIN FETCH ta.user JOIN FETCH ta.project WHERE ta.project.id = :projectId AND ta.deleted = false")
    List<TeamAssignment> findActiveByProjectId(Long projectId);

    @Query("SELECT ta FROM TeamAssignment ta JOIN FETCH ta.user JOIN FETCH ta.project WHERE ta.user.id = :userId AND ta.deleted = false")
    List<TeamAssignment> findActiveByUserId(Long userId);

    boolean existsByProjectIdAndUserIdAndDeletedFalse(Long projectId, Long userId);
}

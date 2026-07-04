package com.pms.user.repository;

import com.pms.user.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    @Query("SELECT r FROM Resource r JOIN FETCH r.user u WHERE r.deleted = false ORDER BY u.lastName")
    List<Resource> findAllActive();

    @Query("SELECT r FROM Resource r JOIN FETCH r.user WHERE r.user.id = :userId AND r.deleted = false")
    Optional<Resource> findActiveByUserId(Long userId);

    @Query("SELECT r FROM Resource r JOIN FETCH r.user WHERE r.user.id IN :userIds AND r.deleted = false")
    List<Resource> findActiveByUserIdIn(java.util.Collection<Long> userIds);
}

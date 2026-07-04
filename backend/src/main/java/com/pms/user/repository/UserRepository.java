package com.pms.user.repository;

import com.pms.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u JOIN FETCH u.role r JOIN FETCH r.permissions WHERE u.email = :email AND u.deleted = false")
    Optional<User> findActiveByEmailWithRole(String email);

    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.deleted = false ORDER BY u.lastName, u.firstName")
    List<User> findAllActive();

    boolean existsByEmail(String email);
}

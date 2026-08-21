package com.pms.user.repository;

import com.pms.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u JOIN FETCH u.role r JOIN FETCH r.permissions WHERE u.email = :email AND u.deleted = false")
    Optional<User> findActiveByEmailWithRole(String email);

    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.deleted = false ORDER BY u.lastName, u.firstName")
    List<User> findAllActive();

    @Query(value = "SELECT u FROM User u JOIN FETCH u.role WHERE u.deleted = false",
           countQuery = "SELECT COUNT(u) FROM User u WHERE u.deleted = false")
    Page<User> findAllActivePaged(Pageable pageable);

    /**
     * Recherche paginée avec filtres optionnels (tous nullables) :
     * texte sur nom/prénom/email, rôle, et statut actif/inactif.
     */
    @Query(value = """
            SELECT u FROM User u JOIN FETCH u.role r
            WHERE u.deleted = false
              AND (:search = ''
                   OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.lastName)  LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.email)     LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:roleId IS NULL OR r.id = :roleId)
              AND (:active IS NULL OR u.active = :active)
            """,
           countQuery = """
            SELECT COUNT(u) FROM User u
            WHERE u.deleted = false
              AND (:search = ''
                   OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.lastName)  LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.email)     LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:roleId IS NULL OR u.role.id = :roleId)
              AND (:active IS NULL OR u.active = :active)
            """)
    Page<User> searchActive(String search, Long roleId, Boolean active, Pageable pageable);

    boolean existsByEmailAndDeletedFalse(String email);

    /** Nombre d'utilisateurs non supprimés portant un rôle donné (garde-fou de suppression de rôle). */
    long countByRoleIdAndDeletedFalse(Long roleId);
}

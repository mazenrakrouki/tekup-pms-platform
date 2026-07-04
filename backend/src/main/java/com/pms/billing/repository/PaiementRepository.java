package com.pms.billing.repository;

import com.pms.billing.entity.Paiement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaiementRepository extends JpaRepository<Paiement, Long> {

    @Query("SELECT p FROM Paiement p WHERE p.jalon.id = :jalonId AND p.deleted = false ORDER BY p.datePaiement")
    List<Paiement> findActiveByJalonId(Long jalonId);

    @Query("SELECT p FROM Paiement p WHERE p.id = :id AND p.deleted = false")
    Optional<Paiement> findActiveById(Long id);

    @Query("SELECT COALESCE(SUM(p.montantRecu), 0) FROM Paiement p WHERE p.jalon.id = :jalonId AND p.deleted = false")
    BigDecimal sumMontantByJalonId(Long jalonId);
}

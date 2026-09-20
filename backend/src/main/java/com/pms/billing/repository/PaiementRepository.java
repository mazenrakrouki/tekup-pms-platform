package com.pms.billing.repository;

// A "paiement" is one amount actually received from a client against a billing milestone;
// a milestone can be paid in instalments, hence a row of its own. sumMontantByJalonId() is
// what lets JalonService recompute a milestone's status (PREVU/FACTURE/PAYE) after every
// payment added or cancelled, so the status can never drift from the money received.

import com.pms.billing.entity.Paiement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Read/write access to Paiement; Spring Data JPA generates the implementation at boot. */
public interface PaiementRepository extends JpaRepository<Paiement, Long> {

    // No JOIN FETCH: jalon is lazy but PaiementMapper only reads the proxy's own id.
    /** Payment history of one milestone, oldest first, live payments only. */
    @Query("SELECT p FROM Paiement p WHERE p.jalon.id = :jalonId AND p.deleted = false ORDER BY p.datePaiement")
    List<Paiement> findActiveByJalonId(Long jalonId);

    // Milestone/project ownership checks happen in PaiementService.delete(), not here.
    /** One payment by id, only if not soft-deleted. */
    @Query("SELECT p FROM Paiement p WHERE p.id = :id AND p.deleted = false")
    Optional<Paiement> findActiveById(Long id);

    // COALESCE avoids NULL (SQL SUM over zero rows) breaking the caller's compareTo().
    // BigDecimal, not double: montantRecu is NUMERIC(15,2) and must stay exact to the cent.
    /**
     * Total actually received on one milestone, live payments only (0 if none).
     * Drives JalonService.recalculerStatut(): reaching the milestone amount flips it to PAYE.
     */
    @Query("SELECT COALESCE(SUM(p.montantRecu), 0) FROM Paiement p WHERE p.jalon.id = :jalonId AND p.deleted = false")
    BigDecimal sumMontantByJalonId(Long jalonId);
}

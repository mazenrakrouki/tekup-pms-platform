package com.pms.billing.repository;

// A "jalon de facturation" is one step of a project's payment plan (percentage, amount,
// status PREVU/FACTURE/PAYE). Amounts are stored but always recomputed from
// effectiveBudget x pourcentage / 100 when the budget moves (H-4), so an invoiced amount
// never drifts from what was printed on the invoice.

import com.pms.billing.entity.JalonFacturation;
import com.pms.billing.entity.JalonStatut;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Read/write access to JalonFacturation; Spring Data JPA generates the implementation at boot. */
public interface JalonFacturationRepository extends JpaRepository<JalonFacturation, Long> {

    // JOIN FETCH avoids N+1 queries (project is lazy and JalonMapper always reads project.code).
    // deleted = false filters soft-deleted milestones; NULLS LAST + id tie-breaker keep the
    // sort identical between H2 (tests) and PostgreSQL (prod).
    /**
     * The full payment plan of one project, sorted by planned date, with Project pre-loaded.
     */
    @Query("SELECT j FROM JalonFacturation j JOIN FETCH j.project WHERE j.project.id = :projectId AND j.deleted = false ORDER BY j.datePrevue NULLS LAST, j.id")
    List<JalonFacturation> findActiveByProjectId(Long projectId);

    // No project filter here on purpose: that check lives in the service layer (ADR-021,
    // two independent barriers). Optional forces callers to handle "not found" explicitly.
    /** One milestone by id, only if not soft-deleted. */
    @Query("SELECT j FROM JalonFacturation j JOIN FETCH j.project WHERE j.id = :id AND j.deleted = false")
    Optional<JalonFacturation> findActiveById(Long id);

    // COALESCE avoids NULL (SQL SUM over zero rows) breaking the caller's arithmetic.
    // BigDecimal, not double: pourcentage is NUMERIC(5,2) and must stay exact.
    /**
     * Total percentage already used by the live milestones of a project (0 if none).
     * Enforces the "payment plan cannot exceed 100%" rule, alongside the DB CHECK constraint.
     */
    @Query("SELECT COALESCE(SUM(j.pourcentage), 0) FROM JalonFacturation j WHERE j.project.id = :projectId AND j.deleted = false")
    BigDecimal sumPourcentageByProjectId(Long projectId);

    // Derived query (no JOIN FETCH needed: caller only reads/writes pourcentage/montant).
    /**
     * H-4: PREVU milestones whose amount must be recomputed when the effective budget changes.
     * Called from AvenantService create/delete via JalonService.recomputePrevuMontants().
     */
    List<JalonFacturation> findByProjectIdAndStatutAndDeletedFalse(Long projectId, JalonStatut statut);
}

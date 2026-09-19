package com.pms.billing.repository;

/*
 * ============================================================================
 *  PaiementRepository - the database door for the payments received from clients.
 * ============================================================================
 *
 *  WHAT THIS FILE IS
 *  A "paiement" is one amount of money actually received from the client against one
 *  billing milestone (a "jalon"): a bank transfer, a cheque, with its date and its
 *  bank reference. A single milestone may be paid in several instalments, which is
 *  why a payment is a row of its own and not a column on the milestone. This
 *  interface is the only place in the application that reads the "paiements" table.
 *
 *  WHERE IT SITS IN THE FLOW (who calls it, what it calls next)
 *    Angular billing page
 *      -> BillingController, mapped on /api/projects/{projectId}/jalons/{jalonId}/paiements
 *      -> ProjectScopeInterceptor. ADR-021: on every URL matching
 *         /api/projects/{id}/ followed by anything, the interceptor checks BOTH the
 *         permission AND the project scope. Holding MANAGE_BILLING is not enough on
 *         its own.
 *      -> PaiementService, which carries the permission test on the method itself
 *         (hasAuthority('VIEW_BILLING') to read, hasAuthority('MANAGE_BILLING') to
 *         write). Authorization is dynamic and permission-based - the code never tests
 *         a role name, only a permission an administrator can re-assign at runtime.
 *      -> PaiementRepository (this file)
 *      -> PostgreSQL table "paiements", created by the Flyway migration
 *         V9__schema_billing.sql.
 *  Entities coming back go to PaiementMapper, which builds the PaiementResponse
 *  records sent to Angular.
 *
 *  ITS SPECIAL ROLE: IT DECIDES WHEN A MILESTONE IS "PAID"
 *  The third method of this file, sumMontantByJalonId(), is read by
 *  JalonService.recalculerStatut() after every payment added or removed. If the total
 *  received reaches the amount of the milestone, the milestone becomes PAYE; if a
 *  payment is later cancelled and the total drops back below, a milestone that was PAYE
 *  returns to FACTURE (or to PREVU when it carries no invoice date).
 *  So the status is never typed in by a user: it is recomputed from the payment rows
 *  after every payment added or cancelled, and then saved in the statut column of the
 *  milestone. Say it that way in front of a jury - the value is stored, but it is always
 *  rebuilt from the rows, so it cannot drift away from the money actually received.
 *
 *  WHY IT EXISTS (what breaks if you delete it)
 *  Spring Data JPA writes the implementation of this interface at start-up, so no SQL
 *  has to be written by hand for payments. Remove this file and PaiementService and
 *  JalonService stop compiling: nothing could be recorded as received, and every
 *  milestone would stay FACTURE for ever, even fully paid ones.
 *
 *  SOFT DELETE, AGAIN
 *  A payment is never physically removed. PaiementService.delete() only sets
 *  deleted = true (column inherited from BaseEntity), because cash history must stay
 *  auditable. So each query here filters "deleted = false" by itself: the inherited
 *  findAll()/findById() know nothing about that flag and would bring a cancelled
 *  payment back into the totals.
 */

import com.pms.billing.entity.Paiement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Read and write access to the Paiement entity. It gives back either payment entities
 * (one, or a List) or a single computed BigDecimal total.
 *
 * WHY AN INTERFACE WITH NO BODY
 * We extend JpaRepository&lt;Paiement, Long&gt;. The two types between the angle
 * brackets are "generics": the entity handled, and the type of its primary key (Long,
 * from BaseEntity.id). Spring Data JPA generates the implementing class when the
 * application boots, so save(), findById(), count() and the rest already exist. A
 * handwritten DAO with an EntityManager and SQL strings would be the alternative; it
 * would repeat the same boilerplate for every entity, and a wrong column name would
 * only show up at runtime instead of at start-up.
 */
public interface PaiementRepository extends JpaRepository<Paiement, Long> {

    // The annotation below holds JPQL, a query language written in terms of Java classes
    // and fields ("Paiement p", "p.datePaiement"), which Hibernate translates into SQL
    // over the table paiements.
    //
    // WHERE p.jalon.id = :jalonId: keep only the payments attached to one milestone.
    // Reading p.jalon.id needs no join at all - jalon_id is already a column of the
    // paiements row, so Hibernate takes the value from the row it is reading.
    // Example without it: the payment history of one milestone would list every payment
    // of the company, and the client would see amounts from other projects.
    //
    // Notice there is no JOIN FETCH here, unlike in the two other repositories of this
    // folder, and that is not an oversight. Paiement.jalon is lazy, so Hibernate hands
    // back a stand-in object (a "proxy") instead of the real milestone - but
    // PaiementMapper only reads jalon.id to fill PaiementResponse.jalonId, and a proxy
    // already knows its own id without going back to the database. Adding JOIN FETCH
    // would load a full milestone row per payment for nothing.
    //
    // AND p.deleted = false: hide the soft-deleted payments (see the header block).
    // Example without it: a payment of 20 000 TND entered twice by mistake and then
    // cancelled would still appear in the history, and the client would believe he paid
    // 20 000 more than he did.
    //
    // ORDER BY p.datePaiement: oldest payment first, so the history reads like a bank
    // statement. Why it is needed: a database returns rows in no guaranteed order.
    // Example without it: two refreshes of the same screen could show the instalments in
    // a different order, and nobody could tell which one settled the milestone.
    /**
     * The payment history of one milestone - every payment still alive - oldest first.
     *
     * Called by PaiementService.findByJalon(), after that service has verified that the
     * milestone really belongs to the project named in the URL.
     */
    @Query("SELECT p FROM Paiement p WHERE p.jalon.id = :jalonId AND p.deleted = false ORDER BY p.datePaiement")
    List<Paiement> findActiveByJalonId(Long jalonId);

    // p.deleted = false again, so that a payment already cancelled cannot be cancelled a
    // second time (which would subtract the same amount twice from the milestone total).
    //
    // Optional<Paiement> rather than a plain entity: Optional is a box that is either
    // full or empty, and the compiler forces the caller to open it before using what is
    // inside. Why: an unknown id is a normal situation, not a bug.
    // PaiementService.delete() writes .orElseThrow(() -> new NotFoundException(...)) and
    // the user gets a clean 404 "Paiement introuvable".
    // Example without Optional: the method returns null, a caller forgets to test it, and
    // a stale page in the browser produces a NullPointerException 500 instead of a clear
    // message.
    //
    // Deliberately, this query filters on the payment id alone - neither on the milestone
    // nor on the project. Those two checks live in PaiementService.delete(), which first
    // verifies that the milestone belongs to the project, then that
    // paiement.getJalon().getId() equals the jalonId from the URL. Reading getJalon()
    // .getId() on a lazy proxy costs no extra query, so the check is free.
    // Example without that chain of checks: a user allowed on project A could call
    // /api/projects/A/jalons/{his own jalon}/paiements/{id of a payment of project B} and
    // erase a payment recorded on a project he cannot even see.
    /**
     * One payment by its id, only if it has not been soft-deleted.
     *
     * Called by PaiementService.delete() before it flips the deleted flag and asks
     * JalonService to recompute the status of the milestone.
     */
    @Query("SELECT p FROM Paiement p WHERE p.id = :id AND p.deleted = false")
    Optional<Paiement> findActiveById(Long id);

    // SUM(p.montantRecu): the database adds the instalments up and returns one number,
    // instead of Java loading every payment row just to total them.
    // Example without it: a milestone paid in twelve monthly instalments would load
    // twelve objects into memory on every single status recomputation.
    //
    // COALESCE(..., 0): COALESCE returns the first of its arguments that is not null, so
    // a null sum becomes zero. This is the whole point of the line.
    // Why: in SQL, SUM over zero rows gives NULL, not 0, and the caller
    // (JalonService.recalculerStatut) immediately calls total.compareTo(jalon.getMontant())
    // on the result.
    // Example without COALESCE: cancelling the only payment of a milestone leaves no live
    // row, SUM returns NULL, and compareTo throws a NullPointerException. The whole
    // transaction is rolled back, so the user cannot cancel that payment at all and the
    // milestone stays marked PAYE while the money is not really there.
    //
    // AND p.deleted = false: cancelled payments must not count as money received. This is
    // what allows a milestone to move back from PAYE to FACTURE.
    // Example without it: a payment entered by mistake, then cancelled, would keep the
    // milestone marked PAYE, and the company would stop chasing an invoice that is in
    // fact still unpaid.
    //
    // BigDecimal, not double: amounts are stored as NUMERIC(15,2) and must be exact to
    // the cent. Example with double: 0.1 + 0.2 gives 0.30000000000000004, so a fully paid
    // milestone could come out one hundredth short of its target and never flip to PAYE.
    /**
     * Total actually received on one milestone, adding up only its live payments.
     * Returns 0 (never null) when the milestone has no payment yet.
     *
     * Called by JalonService.recalculerStatut() after every payment created or deleted:
     * if this total reaches the amount of the milestone the status becomes PAYE. If it
     * does not, the status is only changed when the milestone was PAYE until now - it
     * then goes back to FACTURE, or to PREVU when no invoice date was set. A milestone
     * that was never paid simply keeps the status it already had.
     */
    @Query("SELECT COALESCE(SUM(p.montantRecu), 0) FROM Paiement p WHERE p.jalon.id = :jalonId AND p.deleted = false")
    BigDecimal sumMontantByJalonId(Long jalonId);
}

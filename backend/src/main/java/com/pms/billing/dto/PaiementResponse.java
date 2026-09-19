package com.pms.billing.dto;

/*
 * =============================================================================
 * FILE: PaiementResponse -- the JSON the API sends back for one payment
 * received against a billing milestone.
 *
 * WHAT IT IS
 *   The read-side DTO of a payment (DTO = Data Transfer Object: a small object
 *   whose only job is to carry data out of the application). It is the flat,
 *   public picture of a Paiement row.
 *
 * WHERE IT SITS IN THE FLOW
 *   PaiementRepository gives Paiement entities to PaiementService
 *     -> PaiementService calls PaiementMapper.toResponse(...) /
 *        toResponseList(...) (MapStruct generates that mapper at build time)
 *     -> BillingController returns it from
 *        GET  /api/projects/{projectId}/jalons/{jalonId}/paiements
 *        POST /api/projects/{projectId}/jalons/{jalonId}/paiements
 *     -> Jackson turns it into JSON
 *     -> Angular reads it as the interface Paiement in
 *        core/models/billing.model.ts and lists the payments under a milestone.
 *
 * WHY IT EXISTS (what would break if you deleted it)
 *   Returning the Paiement entity would expose its LAZY link to
 *   JalonFacturation. Jackson serializes after the transaction is closed, so
 *   reading that link throws LazyInitializationException (HTTP 500); and if it
 *   did load, every payment row would carry the whole milestone and, behind it,
 *   the project. A caller who is only allowed to look at payments would then
 *   receive contract data he never asked for. This record keeps the payload
 *   flat and decides, field by field, what the outside world may see.
 *
 * WHY THERE IS NO projectId HERE (unlike JalonResponse and AvenantResponse)
 *   A payment is always read through the URL of its milestone, which already
 *   contains the project id, so repeating it in the body would add nothing. The
 *   scope check of that URL is done by ProjectScopeInterceptor (ADR-021), and
 *   PaiementService checks on top that the milestone really belongs to the
 *   project in the URL.
 * =============================================================================
 */

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * A Java record: immutable, constructor and accessors written by the compiler.
 * No validation annotations: Bean Validation guards what comes IN, and this
 * object only goes OUT.
 */
public record PaiementResponse(
        // Primary key of the payment row. The front end needs it to build
        // DELETE /api/projects/{projectId}/jalons/{jalonId}/paiements/{id}.
        Long id,
        /*
         * WHAT: the id of the milestone this payment belongs to. It is
         * FLATTENED from the entity association by PaiementMapper, with
         * @Mapping(target = "jalonId", source = "jalon.id").
         * WHY only the id: the caller already has the milestone on screen, so
         * sending the object again would repeat the same data on every row. The
         * mapper also runs inside the service transaction, so the LAZY proxy is
         * read while the database session is still open.
         * EXAMPLE without this flattening: the endpoint answers 500
         * (LazyInitializationException), or three payments of the same milestone
         * each carry a full copy of that milestone and of its project.
         */
        Long jalonId,
        /*
         * The money received, exactly as it was saved. BigDecimal keeps the
         * exact decimal digits of the column NUMERIC(15,2), so the total the
         * browser displays matches the total the server used when it decided
         * whether the milestone becomes PAYE.
         */
        BigDecimal montantRecu,
        // The date the money arrived (value date of the transfer). Jackson
        // writes it as the ISO string "2026-03-14", which the Angular model
        // declares as a string.
        LocalDate datePaiement,
        // Bank or accounting reference of the transfer. Can be null, because it
        // is optional on the way in, so the table must handle an empty cell.
        String reference
) {}

package com.pms.billing.dto;

/*
 * =============================================================================
 * FILE: AvenantRequest -- the JSON body a client sends to add a contract
 * amendment ("avenant") to a project.
 *
 * WHAT IT IS
 *   One DTO (Data Transfer Object: a small object whose only job is to carry
 *   data between the outside world and the inside of the application). It
 *   describes the exact set of fields a caller is allowed to send. An "avenant"
 *   is a signed amendment to the contract: the client and the company agree to
 *   change the money of the contract, and sometimes the number of work days
 *   sold with it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular BillingService.createAvenant()
 *     -> POST /api/projects/{projectId}/avenants
 *     -> BillingController.createAvenant(..., @Valid @RequestBody AvenantRequest)
 *     -> AvenantService.create(projectId, request), which requires the
 *        permission MANAGE_BILLING, moves the project's revised budget, asks
 *        JalonService to recompute the amount of every billing milestone still
 *        in status PREVU (marker H-4), and finally saves the new Avenant row --
 *        all in one transaction.
 *   The answer travels back in the sister record AvenantResponse.
 *   There is no "update avenant" endpoint (create and delete only), so this
 *   record is used on creation only.
 *
 * WHY IT EXISTS (what would break if you deleted it)
 *   The controller would then have to accept the Avenant entity itself. A
 *   caller could post {"id": 7, "deleted": true} and reach database columns
 *   that are none of his business. This record is the white list of the five
 *   fields the outside world may send, and nothing else is read from the body.
 *
 * WHY THERE IS NO projectId FIELD IN HERE
 *   The project is always taken from the URL, never from the body. The URL
 *   /api/projects/{projectId}/** is guarded by ProjectScopeInterceptor
 *   (ADR-021), which checks the permission AND that this user is inside the
 *   scope of this project. If the project id also lived in the body, a user
 *   could call the URL of a project he is allowed on while writing another
 *   project id in the body: the scope check would inspect the harmless id from
 *   the URL and the write would land in a project he must never touch.
 * =============================================================================
 */

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * A Java "record": a final, immutable class where the fields, the constructor,
 * the accessors (numero(), montant(), ...), equals, hashCode and toString are
 * written by the compiler.
 *
 * WHY a record rather than a normal class with setters: the body of an HTTP
 * request must never change after Spring has read it. If it were mutable, some
 * code could quietly rewrite montant between the validation step and the save,
 * and the value that was checked would not be the value that is stored.
 * Jackson (the JSON library) builds the record through this constructor, so the
 * JSON keys must match the component names exactly ("dateAvenant", not "date").
 *
 * The rules below (@NotBlank, @NotNull) only run because BillingController
 * marks the parameter with @Valid. Without that @Valid they are dead
 * decoration and every invalid body would travel straight to the database.
 * A failed rule is turned into HTTP 400 with the field name by
 * GlobalExceptionHandler.handleValidation.
 */
public record AvenantRequest(
        /*
         * WHAT: @NotBlank refuses null, "" and a string made only of spaces.
         * WHY: numero is the human reference of the signed paper ("AV-2026-01")
         * and the column avenants.numero is NOT NULL (V9__schema_billing.sql).
         * EXAMPLE without it: someone posts "numero": "   "; the project now
         * carries an amendment of +30 000 that nobody can match with any signed
         * document when the accountant asks where the extra budget comes from.
         */
        @NotBlank String numero,
        /*
         * Free text describing what the amendment is about ("extra Reporting
         * module"). Optional on purpose: the money and the date are the
         * contractual facts, the wording only helps humans read the table.
         * Column: VARCHAR(500), nullable.
         */
        String objet,
        /*
         * WHAT: the money the amendment adds to the contract. Only @NotNull is
         * set, and that is deliberate: the sign carries meaning. Positive means
         * the contract grows, negative means it shrinks. AvenantService adds
         * this value to the project's effective budget when the amendment is
         * created and subtracts it again when the amendment is deleted.
         * WHY no @Positive here (unlike PaiementRequest.montantRecu): a
         * @Positive would make it impossible to record a reduction, and the
         * team would end up encoding a cut in some other, untraceable way.
         * WHY BigDecimal and not double: double cannot hold 0.1 exactly, so
         * sums of amounts drift (0.1 + 0.2 gives 0.30000000000000004).
         * BigDecimal keeps the exact decimal digits, which is what the column
         * NUMERIC(15,2) stores.
         * EXAMPLE: a cut of 10 000 sent as 10000 instead of -10000 raises the
         * budget instead of lowering it, and because milestone amounts are
         * recomputed from that budget (H-4), every future invoice line is wrong.
         */
        @NotNull BigDecimal montant,
        /*
         * WHAT: how much the amendment changes the sold workload, counted in
         * man-days (JH = jours-homme). Optional, because many amendments are
         * about money only.
         * WHY: it is stored on the Avenant row (column added by
         * V15__avenant_workload.sql) and shown in the amendments table of the
         * billing screen. No budget calculation uses it; it is a contractual
         * fact that the Excel model tracks next to the money.
         * EXAMPLE without it: the client buys 45 extra man-days, the budget
         * grows but the sold workload stays at the old figure, so every "days
         * sold versus days spent" reading makes a healthy project look like it
         * has massively overrun.
         */
        BigDecimal workloadDays,
        /*
         * WHAT: @NotNull forces the date the amendment was signed. It is the
         * contractual date, not the day somebody typed the row in.
         * WHY LocalDate and not LocalDateTime: a contract is signed on a day; an
         * hour would add noise and a time-zone problem for no benefit.
         * EXAMPLE without it: with a null date two amendments on the same
         * project can no longer be put in order, and the question "what was the
         * budget of this project in March?" has no answer at all.
         */
        @NotNull LocalDate dateAvenant
) {}

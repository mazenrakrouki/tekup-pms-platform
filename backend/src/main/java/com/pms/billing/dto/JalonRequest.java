package com.pms.billing.dto;

/*
 * =============================================================================
 * FILE: JalonRequest -- the JSON body sent to create or to edit a billing
 * milestone ("jalon de facturation").
 *
 * WHAT A JALON IS
 *   A contract is rarely invoiced in one go. It is cut into milestones: "30% on
 *   kick-off", "50% on delivery", "20% after acceptance". Each milestone is one
 *   future invoice. This DTO (Data Transfer Object: an object whose only job is
 *   to carry data into the application) describes what a caller may send.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular BillingService.createJalon()
 *     -> POST /api/projects/{projectId}/jalons        (create)
 *     -> PUT  /api/projects/{projectId}/jalons/{id}   (edit)
 *     -> BillingController.createJalon / updateJalon, both with @Valid
 *     -> JalonService.create / JalonService.update, which require the
 *        permission MANAGE_BILLING, check that the sum of the percentages of
 *        the project never goes above 100, compute the amount themselves, and refuse
 *        any edit of a milestone that is no longer in status PREVU.
 *   The saved milestone comes back as a JalonResponse.
 *
 * WHY ONE RECORD FOR BOTH CREATE AND EDIT
 *   The two operations accept exactly the same three fields. A second, almost
 *   identical record would be copy-paste that slowly drifts: someone adds a
 *   rule on one side only, and the same body is then accepted on POST and
 *   refused on PUT.
 *
 * WHY montant, dateFacture AND statut ARE NOT IN HERE (the important part)
 *   - montant is computed by the server: effectiveBudget x pourcentage / 100.
 *     If the client could send it, the milestones of a project would no longer
 *     add up to the contract, and the H-4 recompute (which refreshes the amount
 *     of every PREVU milestone when the budget moves) would immediately
 *     overwrite whatever the client sent, so the screen and the database would
 *     disagree.
 *   - dateFacture and statut only change through the dedicated endpoint
 *     PATCH .../facturer and through the recording of payments. If they were
 *     fields here, a caller could set statut = PAYE with a single PUT, and the
 *     project would look cashed-in with no payment row behind it.
 *
 * WHY THERE IS NO projectId FIELD
 *   The project comes from the URL, and /api/projects/{projectId}/** is guarded
 *   by ProjectScopeInterceptor (ADR-021), which checks the permission AND that
 *   this user is inside the scope of that project. A project id in the body
 *   would let a user pass the scope check on a project he is allowed on while
 *   writing the milestone into a different project.
 * =============================================================================
 */

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * A Java record: final and immutable, with the constructor, the accessors
 * (label(), pourcentage(), datePrevue()), equals, hashCode and toString written
 * by the compiler. Jackson builds it through that constructor, so the JSON keys
 * must match the component names exactly.
 *
 * WHY immutable: the body must stay identical between the moment the rules
 * below accept it and the moment JalonService computes the amount from it.
 *
 * The rules below only run because BillingController marks the parameter with
 * @Valid; a failure becomes HTTP 400 naming the field, through
 * GlobalExceptionHandler.handleValidation.
 */
public record JalonRequest(
        /*
         * WHAT: @NotBlank refuses null, "" and a string of spaces only.
         * WHY: label is what a human reads in the billing table and on the cash
         * forecast ("30% on kick-off"), and the column jalons_facturation.label
         * is NOT NULL (V9__schema_billing.sql).
         * EXAMPLE without it: a milestone saved with an empty label shows an
         * empty row in the table; two such rows cannot be told apart, and the
         * project manager invoices the wrong one.
         */
        @NotBlank String label,
        /*
         * WHAT: the share of the contract this milestone invoices, in percent.
         * Three rules stack here:
         *   @NotNull    -> the value must be present.
         *   @Positive   -> strictly greater than zero (0 and negatives refused).
         *   @DecimalMax -> at most 100. The limit is written as the string "100"
         *                  because an annotation only accepts constants and a
         *                  BigDecimal cannot be one; the text is parsed into an
         *                  exact BigDecimal, so no rounding happens on the way.
         *                  @DecimalMax includes its bound, so exactly 100 is a
         *                  valid single-milestone plan.
         * WHY all three, when the database already has
         * CHECK (pourcentage > 0 AND pourcentage <= 100): the database answers a
         * violation with a vague 409 "constraint violated" written by
         * GlobalExceptionHandler, while these rules answer 400 and name the
         * field, so the Angular form can point at the right input.
         * WHY this is not enough on its own: these rules look at ONE milestone.
         * The rule "all the milestones of a project together stay under 100%"
         * needs the other rows, so it lives in
         * JalonService.validatePourcentageSum.
         * EXAMPLE without @Positive: a milestone at -20% passes, the sum check
         * is happy because the total goes down, and the project ends up with a
         * negative invoice line.
         */
        @NotNull @Positive @DecimalMax("100") BigDecimal pourcentage,
        /*
         * WHAT: the date the milestone is expected to be invoiced. Optional: at
         * the time the billing plan is written, the schedule is not always known.
         * WHY it exists: it is what makes the cash forecast possible, by placing
         * the future invoice on a month.
         * WHY LocalDate: an invoice is planned for a day, not for an hour, so no
         * time and no time zone are involved.
         * EXAMPLE without it: every milestone is only "some day", and nobody can
         * answer "how much will we invoice this quarter?" until each invoice is
         * actually issued.
         */
        LocalDate datePrevue
) {}

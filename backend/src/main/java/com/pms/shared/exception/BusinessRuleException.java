package com.pms.shared.exception;

/**
 * WHAT THIS FILE IS
 * The exception a service throws when the request is well formed but a business rule of
 * the company says "no". Example: "a finished project cannot go back to Active", or
 * "this workload entry is already validated, you cannot change it".
 * GlobalExceptionHandler turns it into HTTP 422 Unprocessable Entity.
 *
 * WHERE IT SITS IN THE FLOW
 *   A service (ProjectService, LivrableService, DemandeChangementService, JalonService,
 *   ChargeReelleService, PlanChargeService, RoleAdminService, TeamAssignmentService,
 *   MissionService, SprintService, BacklogItemService ...)
 *     -> throw new BusinessRuleException("message for the user")
 *     -> the call stack unwinds through the controller without anybody catching it
 *     -> GlobalExceptionHandler.handleBusinessRule (same package, see that file)
 *     -> JSON body { "type": "/errors/business-rule", "status": 422, "detail": "<message>" }
 *     -> the Angular screen shows that "detail" text to the user.
 * The message is read by a human, which is why the services write it in French, the
 * language of the users of the application.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * This class is the answer to audit item M-3. Before it, every refused rule was thrown as
 * an IllegalArgumentException and came back to the browser as 409 Conflict. 409 means
 * "your request fights with data that already exists", which is the right answer for a
 * duplicate project code, and the wrong answer for "this milestone must be in state PREVU
 * before it can be invoiced". With a single exception class the frontend could not tell
 * the two cases apart, so it could not show two different messages. Two classes give two
 * HTTP statuses (422 and 409) and two different "type" URIs. Delete this class and every
 * business refusal falls back into the 409 bucket, and the controller tests that expect
 * 422 (ProjectControllerTest, AgileControllerTest, GovernanceControllerTest) fail.
 *
 * THE THREE STATUSES, SIDE BY SIDE - the line to remember
 *   400 Bad Request  = the request itself is malformed (missing field, wrong type).
 *                      Raised by @Valid, handled by handleValidation.
 *   409 Conflict     = the request collides with a row that already exists
 *                      (same project code, same email). Raised as IllegalArgumentException.
 *   422 Unprocessable= the request is understood and every value is valid, but a rule of
 *                      the business refuses it. THIS class.
 *
 * WHY IT EXTENDS RuntimeException AND NOT Exception
 * RuntimeException is "unchecked": the compiler does not force the callers to catch it or
 * to declare it with "throws". Two reasons for that choice.
 *   1. A rule can be refused ten levels deep inside a service, and none of the methods in
 *      between has anything useful to do about it. A checked exception would add a
 *      "throws BusinessRuleException" clause to the whole chain, up to the controller,
 *      for no gain.
 *   2. Spring rolls a @Transactional method back automatically when an UNCHECKED exception
 *      escapes, but it COMMITS when a checked one escapes. With a checked exception,
 *      refusing a rule in the middle of a method would still save the rows written before
 *      the refusal - for example a deliverable already flagged "validated" while the
 *      milestone it belongs to was refused.
 */
public class BusinessRuleException extends RuntimeException {

    /**
     * Builds the exception from the sentence the user will read.
     *
     * WHY only a message, with no error code and no "cause" exception: the message is the
     * whole contract with the frontend. It is copied as-is into the "detail" field of the
     * ProblemDetail body, and the machine-readable half of the contract is already carried
     * by the "type" URI (/errors/business-rule) that GlobalExceptionHandler attaches. A
     * "cause" would be pointless here because nothing failed underneath: the code decided
     * on purpose to refuse.
     */
    public BusinessRuleException(String message) {
        super(message);
    }
}

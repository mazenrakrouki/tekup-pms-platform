package com.pms.shared.exception;

/**
 * Thrown when a request is well-formed but a business rule refuses it (e.g. a finished
 * project cannot go back to Active). GlobalExceptionHandler maps it to HTTP 422, distinct
 * from the 409 used for data conflicts, so the frontend can tell the two cases apart.
 * Unchecked (extends RuntimeException) so a refusal deep in a service doesn't force a
 * "throws" clause up the whole call chain, and still rolls back the surrounding transaction.
 */
public class BusinessRuleException extends RuntimeException {

    /** Message is shown as-is to the user (in French), so no separate error code is needed. */
    public BusinessRuleException(String message) {
        super(message);
    }
}

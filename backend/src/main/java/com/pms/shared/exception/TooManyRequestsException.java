package com.pms.shared.exception;

/**
 * Thrown only by AuthService.login() when LoginAttemptTracker has recorded 5 failures for
 * the email within 15 minutes; GlobalExceptionHandler maps it to 429. A dedicated class
 * (not BusinessRuleException) so the frontend can tell "locked out" (429) from "wrong
 * password" (401) by status alone. Part of the H-2 brute-force mitigation.
 */
public class TooManyRequestsException extends RuntimeException {

    // Message already includes the wait time, computed from LoginAttemptTracker.WINDOW_SECONDS so it can't drift.
    public TooManyRequestsException(String message) {
        super(message);
    }
}

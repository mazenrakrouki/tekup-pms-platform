package com.pms.shared.exception;

/**
 * WHAT THIS FILE IS
 * The exception thrown when someone tries to log in too many times with the wrong
 * password. GlobalExceptionHandler turns it into HTTP 429 Too Many Requests.
 *
 * WHERE IT SITS IN THE FLOW
 *   Login form (Angular) -> POST /api/auth/login -> AuthController -> AuthService.login()
 *     -> AuthService asks LoginAttemptTracker.isBlocked(email) as its very FIRST step,
 *        before it even looks the user up in the database
 *     -> if the email is blocked: throw new TooManyRequestsException("Trop de tentatives...")
 *     -> GlobalExceptionHandler.handleTooManyRequests (same package)
 *     -> JSON body { "type": "/errors/too-many-requests", "status": 429, "detail": "<message>" }
 *     -> login.component.ts branches on the STATUS (403 -> account disabled,
 *        429 -> auth.login.errors.tooManyAttempts, anything else -> wrong credentials) and
 *        shows the matching Transloco translation, so the user is told to wait instead of
 *        being told again that the password is wrong (audit item N-3).
 * This is the ONLY place in the whole backend that throws it: AuthService.login().
 *
 * WHY IT EXISTS - what would break if you deleted it
 * It is one of the four links of audit item H-2 ("new-account takeover chain"). The
 * counter behind it, LoginAttemptTracker, allows 5 failed attempts per email inside a
 * sliding 15-minute window. Without a way to refuse the 6th attempt, an attacker can try
 * passwords as fast as the network allows; bcrypt makes each try slow, but not slow enough
 * to survive an automated list of common passwords.
 * A dedicated class, rather than reusing BusinessRuleException, because the STATUS is the
 * message here: the browser must be able to tell "wrong password" (401) from "you are
 * locked out for a while" (429) without reading French text. Reuse BusinessRuleException
 * and the user gets a 422 and a login screen that keeps saying "wrong password" while the
 * account is in fact temporarily blocked.
 *
 * WHY IT EXTENDS RuntimeException
 * Unchecked, like the other exceptions of this package: it is thrown from the first line
 * of the service, and no method in between has anything sensible to do with it. It also
 * lets Spring roll back the @Transactional login method - here nothing has been written
 * yet, but the rule stays the same everywhere in the application.
 */
public class TooManyRequestsException extends RuntimeException {

    /**
     * Builds the exception from the sentence that travels back in the "detail" field of
     * the 429 body.
     * WHY the message already contains the waiting time: AuthService computes it from
     * LoginAttemptTracker.WINDOW_SECONDS instead of writing "15" by hand, so the number in
     * the message can never drift away from the window the tracker really enforces. The
     * login screen currently prefers its own translated text, but any other client (a
     * test, Swagger, a future mobile app) reads the exact delay from this detail.
     */
    public TooManyRequestsException(String message) {
        super(message);
    }
}

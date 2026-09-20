package com.pms.shared.exception;

import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Turns any exception that escapes a controller/service into a JSON ProblemDetail (RFC 7807)
 * with the right HTTP status, so no controller needs its own try/catch. Spring Security filters
 * run before this (JwtAuthenticationFilter, FirstLoginFilter, SecurityConfig's entry point
 * handle their own 401s); Spring dispatches here by closest matching exception type.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Wrong email or password -> 401. AuthService uses the same message and timing (dummy
     * bcrypt check) whether the email is unknown or the password is wrong, to avoid user
     * enumeration (H-2). Also used by changePassword() for a wrong current password.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        // Distinct type from handleJwt's /errors/token-invalid so the client can tell
        // "wrong password" from "session expired".
        pd.setType(URI.create("/errors/unauthorized"));
        return pd;
    }

    /**
     * Correct credentials but a deactivated account -> 403, not 401, since retrying the
     * password would not help (login.component.ts shows "contact the administrator" on 403).
     */
    @ExceptionHandler(DisabledException.class)
    public ProblemDetail handleDisabled(DisabledException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        pd.setType(URI.create("/errors/forbidden"));
        return pd;
    }

    /**
     * Refresh token unusable (malformed/expired, wrong kind, or revoked tokenVersion after
     * H-1 rotation) -> 401 with its own type so the frontend distinguishes "session over"
     * from "wrong password". Filter-level JwtException never reaches here (caught earlier).
     */
    @ExceptionHandler(JwtException.class)
    public ProblemDetail handleJwt(JwtException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setType(URI.create("/errors/token-invalid"));
        return pd;
    }

    /**
     * Request body fails Bean Validation (Valid/RequestBody) -> 400 listing all broken
     * fields at once ("field : reason", comma-joined) so the user fixes the form in one pass.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        // getFieldErrors() only (not getGlobalErrors()): fine today since every constraint
        // is field-level, but a future class-level rule would need handling here too.
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " : " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, details);
        pd.setType(URI.create("/errors/validation"));
        return pd;
    }

    /**
     * Unknown or soft-deleted row -> 404 (the two are indistinguishable from outside on
     * purpose). Out-of-scope project access is a 403 from ProjectScopeInterceptor instead
     * (ADR-021), not this handler.
     */
    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("/errors/not-found"));
        return pd;
    }

    /**
     * Valid request refused by a business rule (illegal status change, invoicing outside
     * PREVU, etc.) -> 422, not 400, so the user knows it's a rule, not a typo (M-3). The
     * French message is shown to the user as-is; it is the explanation.
     */
    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("/errors/business-rule"));
        return pd;
    }

    /**
     * Duplicate value caught by a service's own check-before-write (e.g. code/email already
     * used) -> 409. Reuses the JDK's IllegalArgumentException rather than a custom class, so
     * note it also catches any accidental IllegalArgumentException as a 409 instead of 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        // Same type as handleDataIntegrity: duplicate caught in code or by the DB looks the same to the client (C-4).
        pd.setType(URI.create("/errors/conflict"));
        return pd;
    }

    /**
     * Authenticated but refused -> 403. Covers @PreAuthorize permission checks, the
     * ProjectScopeInterceptor perimeter guard (ADR-021, prevents IDOR), and service-level
     * ownership guards. Without this handler these would fall through to a fake 500.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        // Fixed neutral message, not ex.getMessage(): the real message would reveal which internal guard fired.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Accès refusé");
        pd.setType(URI.create("/errors/forbidden"));
        return pd;
    }

    /**
     * A path/query value can't convert to the type the controller expects (e.g. non-numeric
     * id) -> 400 instead of falling through to a fake 500, since this happens before any
     * service or validation runs.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // getValue() may be null; string concat prints "null" safely rather than throwing here.
        String detail = "Valeur invalide pour le paramètre '" + ex.getName() + "' : " + ex.getValue();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create("/errors/validation"));
        return pd;
    }

    /**
     * Unknown URL -> 404. Relies on application.yml's throw-exception-if-no-handler-found
     * and disabled static-resource mapping; without both, this becomes dead code.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ProblemDetail handleNotFound(NoHandlerFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Route introuvable : " + ex.getRequestURL());
        pd.setType(URI.create("/errors/not-found"));
        return pd;
    }

    /**
     * Database rejected the write (unique index, FK, CHECK constraint) -> 409, same type as
     * handleIllegalArgument. Catches races between a service's existsByCode check and the
     * later INSERT that a unique index alone stops (C-4).
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        // Constraint name logged server-side only (WARN, not ERROR: not an app defect) — never sent to the client.
        log.warn("Contrainte de données violée", ex.getMostSpecificCause());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Opération impossible : contrainte de données (doublon ou valeur invalide)");
        pd.setType(URI.create("/errors/conflict"));
        return pd;
    }

    /**
     * 5 failed logins for the same email inside 15 minutes -> 429, so the frontend tells
     * the user to wait instead of repeating "wrong password" (H-2).
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail handleTooManyRequests(TooManyRequestsException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        pd.setType(URI.create("/errors/too-many-requests"));
        return pd;
    }

    /**
     * Catch-all for anything unforeseen -> 500 with a generic message; the real cause is
     * logged server-side only, since an exception message can leak internal details.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Erreur inattendue", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne du serveur");
        pd.setType(URI.create("/errors/internal"));
        return pd;
    }
}

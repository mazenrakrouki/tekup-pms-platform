package com.pms.shared.exception;

/**
 * WHAT THIS FILE IS
 * The single exception the whole backend throws when a row asked for by its id does not
 * exist - or exists but is flagged deleted = true (soft delete), which for the user is the
 * same thing. GlobalExceptionHandler turns it into HTTP 404 Not Found.
 *
 * WHERE IT SITS IN THE FLOW
 *   A repository lookup returns an Optional (a box that either holds the row or is empty)
 *     -> the service writes .orElseThrow(() -> new NotFoundException("Projet introuvable"))
 *     -> the stack unwinds through the controller with nobody catching it
 *     -> GlobalExceptionHandler.handleNotFound (same package)
 *     -> JSON body { "type": "/errors/not-found", "status": 404, "detail": "<message>" }
 *     -> the Angular screen shows "not found" instead of a blank page.
 * It is thrown from about twenty-five services (project, mission, agile, billing,
 * governance, workload, team, user, kpi, devis interne) and from a few repository default
 * methods that wrap the lookup for their callers.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * Without it each service would have to decide by itself what to do with an empty Optional.
 * The two easy answers are both bad: returning null pushes the crash one line further (the
 * next project.getCode() throws a NullPointerException and the user gets a 500 "internal
 * error" for a simple typo in a URL), and throwing a Spring ResponseStatusException would
 * put HTTP codes inside the service layer, where they do not belong - the service would
 * then be impossible to unit-test without the web framework. This class keeps the services
 * free of HTTP and keeps the "unknown id -> 404" decision in ONE place,
 * GlobalExceptionHandler.
 *
 * WHY IT CARRIES NO ID AND NO ENTITY TYPE
 * Only a free-text message. The services already write a readable sentence ("Projet
 * introuvable", "Jalon introuvable"), and the frontend only shows it. Adding fields would
 * mean changing every one of the ~60 call sites for no visible gain.
 *
 * WHY IT EXTENDS RuntimeException
 * RuntimeException is "unchecked": the compiler does not force callers to catch it or to
 * declare "throws". That matters here for two precise reasons.
 *   1. It is thrown from inside the lambda given to Optional.orElseThrow(...). A checked
 *      exception cannot escape that lambda without extra plumbing, so every lookup in the
 *      application would become several lines long.
 *   2. Spring rolls a @Transactional method back automatically when an unchecked exception
 *      escapes, and COMMITS when a checked one escapes. A 404 raised halfway through a
 *      method that had already written a row would otherwise leave that half-written row
 *      in the database.
 */
public class NotFoundException extends RuntimeException {

    /**
     * Builds the exception from the sentence the user will read; that sentence ends up
     * unchanged in the "detail" field of the 404 body.
     * WHY no cause parameter: nothing failed underneath. The database answered correctly -
     * it answered "no row". There is no lower-level exception to attach.
     */
    public NotFoundException(String message) {
        super(message);
    }
}

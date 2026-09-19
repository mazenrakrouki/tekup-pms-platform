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
 * WHAT THIS FILE IS
 * The single place that turns an exception into an HTTP answer. Any exception that escapes
 * a controller - or escapes a service that the controller called - lands in one of the
 * thirteen methods below, and comes back to the browser as a small JSON error body with
 * the right status code. No controller of the application contains a try/catch.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular HttpClient
 *     -> Spring Security filters (JwtAuthenticationFilter, FirstLoginFilter)
 *     -> DispatcherServlet
 *     -> ProjectScopeInterceptor for /api/projects/{id}/** (ADR-021)
 *     -> a @RestController (AuthController, ProjectController, BillingController, ...)
 *     -> a @Service, where the real work and the @PreAuthorize permission check happen
 *     -> something throws
 *     -> the stack unwinds, nobody catches, the DispatcherServlet looks for a matching
 *        handler method (the ones marked with the ExceptionHandler annotation below)
 *     -> THIS FILE builds a ProblemDetail
 *     -> JSON body sent with the content type application/problem+json.
 * Nothing in the application calls this class directly; Spring calls it.
 *
 * ONE IMPORTANT LIMIT: THE SECURITY FILTERS ARE NOT COVERED
 * The Spring Security filters run BEFORE the DispatcherServlet, so an exception thrown
 * inside a filter never reaches this class. That is exactly why:
 *   - JwtAuthenticationFilter catches its own errors and simply leaves the request
 *     anonymous, which then ends as a clean 401;
 *   - FirstLoginFilter writes its {"code":"FIRST_LOGIN_REQUIRED"} JSON by hand (H-2);
 *   - SecurityConfig registers its own authenticationEntryPoint for the "no token at all"
 *     case (401 "Non authentifie").
 * A jury question to expect: "why is the 401 for a missing token not in this file?"
 * Answer: because at that moment the request has not reached Spring MVC yet.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * Spring Boot would fall back to its default error page. Three things would be lost.
 *   1. The status codes would collapse. A refused business rule, an unknown id and a
 *      duplicate code would all become 500, so the frontend could no longer react
 *      differently (a toast for 422, a "not found" page for 404, a redirect for 401).
 *   2. The error body shape would stop being stable, and every Angular service would have
 *      to guess where the message is.
 *   3. Internal details would leak. Several handlers below deliberately replace the
 *      exception message with a neutral sentence and log the real cause on the server
 *      instead (handleDataIntegrity, handleAccessDenied, handleGeneric).
 *
 * TWO WORDS EXPLAINED, BECAUSE THEY COME BACK ON EVERY LINE
 *   ProblemDetail - the Spring class for the standard error body described by RFC 7807
 *                   (updated as RFC 9457). It always carries the same four fields:
 *                   "type" (a URI naming the kind of error), "title", "status" and
 *                   "detail" (the readable sentence). Returning the same shape everywhere
 *                   is what lets the frontend have ONE error helper instead of one per
 *                   endpoint.
 *   "type" URI    - here a short relative path such as /errors/not-found. It is the
 *                   machine-readable half of the answer: a client can branch on it without
 *                   parsing French text. It is not a real page; nothing is served at that
 *                   address.
 *
 * HOW SPRING CHOOSES BETWEEN THE THIRTEEN METHODS
 * It picks the handler whose declared exception type is the CLOSEST parent of the
 * exception that was really thrown. Every method below is therefore more precise than
 * handleGeneric(Exception), which only runs when no other method matches. Order inside the
 * file has no effect; only the types matter.
 *
 * THE STATUS CONTRACT OF THE APPLICATION, IN ONE PLACE
 *   400 validation failed or a path/query value has the wrong type
 *   401 not authenticated, wrong password, or token invalid / expired / revoked
 *   403 authenticated but refused: account disabled, missing permission, project out of
 *       scope (ADR-021)
 *   404 unknown id, soft-deleted row, or unknown route
 *   409 collides with existing data (duplicate code or email, database constraint) - C-4
 *   422 the values are valid but a business rule refuses the request - M-3
 *   429 too many failed logins - H-2
 *   500 anything unexpected; the client gets no detail, the server logs everything
 *
 * THE TWO ANNOTATIONS ON THE CLASS
 *   RestControllerAdvice - WHAT: registers this class as the exception handler of EVERY
 *       RestController of the application (no package filter is given, so the scope is
 *       the whole backend). The "Rest" half also means the returned object is written
 *       straight into the response body as JSON, exactly like ResponseBody on a
 *       controller method. WHY: the alternative is a try/catch in every controller method.
 *       WITHOUT IT: the class becomes an ordinary bean that Spring never calls, and every
 *       error silently turns into Spring Boot's default 500 page.
 *   Slf4j - WHAT: a Lombok annotation that generates the line
 *       "private static final Logger log = LoggerFactory.getLogger(...)". WHY: two
 *       handlers below (handleDataIntegrity, handleGeneric) must write the real cause into
 *       the server log because they refuse to send it to the client. WITHOUT IT, "log"
 *       does not exist and the file does not compile.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Wrong email or wrong password -> 401 Unauthorized.
     *
     * WHO THROWS IT: AuthService only. login() throws it when the email is unknown OR the
     * password does not match - on purpose the SAME message "Identifiants incorrects" in
     * both cases, and after running bcrypt in both cases (bcrypt is the slow algorithm that
     * hashes passwords; AuthService compares a dummy hash when the email is unknown so that
     * the answer takes the same time), so that neither the text nor the response time tells
     * an attacker whether an email exists in the database - that attack is called user
     * enumeration and closing it is part of audit item H-2.
     * changePassword() throws the same exception when the current password is wrong.
     *
     * WHY 401 and not 403: 401 means "I do not know who you are, try to identify yourself
     * again"; 403 means "I know who you are, and you are not allowed". Here the identity
     * itself failed.
     *
     * WHY the exception message is copied into the body: every BadCredentialsException of
     * this application is created by our own code with a short French sentence, so nothing
     * technical can leak. The same shortcut would be dangerous for an exception coming from
     * a library, which is why handleGeneric further down does the opposite.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        // Builds the RFC 7807 body: status 401 plus the readable sentence in "detail".
        // Spring fills "title" by itself from the status ("Unauthorized").
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        // The stable machine-readable name of this error. The login page reads the status,
        // but this URI is what keeps 401-for-bad-password and 401-for-dead-token apart
        // (see handleJwt, which sets /errors/token-invalid). Without two different URIs a
        // client could not tell "type your password again" from "your session ended".
        pd.setType(URI.create("/errors/unauthorized"));
        return pd;
    }

    /**
     * The email and the password are correct, but the account has been switched off
     * -> 403 Forbidden.
     *
     * WHO THROWS IT: AuthService.login(), after the password check succeeded, when
     * user.isActive() is false ("Compte desactive"). An administrator deactivates a user
     * instead of deleting him, so that his history (workload, validations, audit columns)
     * stays readable.
     *
     * WHY 403 and not 401: sending 401 would tell the person "try again", and he would
     * retype a password that is in fact perfectly right. 403 says "you are identified, the
     * door is closed for you". login.component.ts uses exactly this difference: on 403 it
     * shows "account disabled, contact the administrator" (audit item N-3).
     *
     * WHY this is not a security leak: the caller already proved he owns the account by
     * giving the right password, so learning that the account is disabled teaches him
     * nothing he could use.
     */
    @ExceptionHandler(DisabledException.class)
    public ProblemDetail handleDisabled(DisabledException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        // Same "type" as handleAccessDenied: for a client both are "you are known and
        // refused". The status code and the detail carry the difference.
        pd.setType(URI.create("/errors/forbidden"));
        return pd;
    }

    /**
     * The refresh token is not usable -> 401 Unauthorized.
     *
     * WHO THROWS IT: AuthService.refresh(), which is reached by POST /api/auth/refresh
     * when the short-lived access token has expired and the browser sends its HttpOnly
     * refresh cookie. Three different situations end here:
     *   - the token is malformed, expired or badly signed ("Refresh token invalide ou
     *     expire");
     *   - it is a real token but of the wrong kind, an access token replayed as a refresh
     *     token ("Type de token incorrect");
     *   - its tokenVersion claim ("claim" = one named value written inside the token) is
     *     older than the counter stored on the user row, which happens after a logout, a
     *     password change, a role change, or after a normal refresh, because H-1 rotates
     *     the refresh token and bumps the counter every single time ("Token revoque").
     * A JwtException raised inside JwtAuthenticationFilter does NOT arrive here: the filter
     * runs before Spring MVC and catches its own errors.
     *
     * WHY a separate handler from handleBadCredentials, since both answer 401: the "type"
     * URI is different. /errors/token-invalid means "your session is over, ask for a new
     * one or go back to the login page"; /errors/unauthorized means "your password is
     * wrong". Without the split, the frontend would show "wrong password" to a user whose
     * session simply expired while he was reading a page.
     *
     * WHY the type comes from the jjwt library (io.jsonwebtoken.JwtException) instead of a
     * custom class: the library already throws it when it fails to parse or to verify a
     * token, and AuthService reuses the same type for its own two refusals (wrong kind,
     * revoked version). One type means one handler and one answer, whatever noticed the
     * problem.
     */
    @ExceptionHandler(JwtException.class)
    public ProblemDetail handleJwt(JwtException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setType(URI.create("/errors/token-invalid"));
        return pd;
    }

    /**
     * The JSON body sent by the browser breaks a validation rule -> 400 Bad Request.
     *
     * WHO THROWS IT: Spring itself. Every controller marks its body parameter with the
     * two annotations Valid and RequestBody; Spring then runs the checks carried by the
     * request record (NotNull, NotBlank, Size, Positive, Email, DecimalMin ...) and,
     * as soon as one fails, throws MethodArgumentNotValidException before the controller
     * method even starts. So the service never sees an invalid object.
     *
     * WHAT IT GIVES BACK: one 400 that lists ALL the broken fields at once, as a single
     * sentence built as "field : reason" pieces joined by commas, for example:
     *   "code : must not be blank, name : size must be between 0 and 255"
     * The "reason" half is the message of the annotation itself: the standard Bean
     * Validation text when the record does not set one, or the French sentence written by
     * hand when it does (RoleRequest is the example: "Le nom du role est requis.").
     *
     * WHY all of them in one string, and not the first one only: the user fixes his form
     * in one pass instead of submitting five times and being refused five times.
     *
     * WHY 400 and not 422: nothing here is a business decision. The request itself is
     * incomplete or malformed. 422 is kept for a request that is perfectly formed and
     * still refused by a rule (see handleBusinessRule, audit item M-3).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        // getBindingResult() is the report of the validation: it holds one FieldError per
        // broken rule. .stream() walks that list.
        // NOTE on getFieldErrors(): it returns the errors attached to a FIELD. Errors
        // attached to the whole object (getGlobalErrors(), produced by a class-level
        // constraint) are not in this list. Today every constraint of the project sits on
        // a field, so nothing is lost; a class-level rule added later would have to be
        // added here too, otherwise its message would silently disappear from the answer.
        String details = ex.getBindingResult().getFieldErrors().stream()
                // Turns one error object into readable text: the name of the guilty field,
                // then the message of the annotation. The field name matters because the
                // Angular form uses it to highlight the right input. Without it the user
                // would read "must not be empty" without knowing WHICH box is empty.
                .map(e -> e.getField() + " : " + e.getDefaultMessage())
                // Glues the pieces with ", " into ONE string, because ProblemDetail.detail
                // is a single text field, not a list.
                .collect(Collectors.joining(", "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, details);
        // Same "type" as handleTypeMismatch: from the client's point of view both mean
        // "the request you sent is not acceptable, fix the form".
        pd.setType(URI.create("/errors/validation"));
        return pd;
    }

    /**
     * The row asked for by its id does not exist -> 404 Not Found.
     *
     * WHO THROWS IT: the services, through the pattern
     * repository.findActiveById(id).orElseThrow(() -> new NotFoundException("...")).
     * See NotFoundException in this same package for the full list of callers.
     *
     * WHY a soft-deleted row also lands here: the repositories filter out deleted = true,
     * so a deleted project answers "empty" exactly like an id that never existed. That is
     * wanted - the row still lives in the database for the audit trail, but for the user it
     * is gone, and the two cases must be impossible to tell apart from outside.
     *
     * WHY the exception message is sent as-is: the services write it themselves ("Projet
     * introuvable"), so it contains no technical detail, and it never contains the id.
     *
     * NOTE for the jury on ADR-021: asking for a project you are not allowed to see does
     * NOT come here. ProjectScopeInterceptor runs before the controller on every
     * /api/projects/{id}/** URL and refuses with 403 (see handleAccessDenied), because an
     * id outside your perimeter is simply absent from your list of accessible ids - and so
     * is an id that does not exist. A caller who has portfolio-wide access skips that check
     * and is the one who really gets this 404 on an unknown id. In short: 403 means "not
     * for you", 404 means "does not exist", and a limited user cannot tell the two apart,
     * which is exactly what we want.
     */
    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("/errors/not-found"));
        return pd;
    }

    /**
     * A rule of the business refuses the request -> 422 Unprocessable Entity.
     *
     * WHO THROWS IT: the services, when the values are all valid but the state of the data
     * forbids the action. Real examples from the code: an illegal project status change
     * (ProjectStatus.canTransitionTo), invoicing a milestone that is not in state PREVU,
     * changing a workload entry that is already validated, deleting a role that is still
     * given to at least one user, recording work on a project you are not a member of.
     *
     * WHY 422 and not 400: 400 would say "your request is malformed", and the user would
     * look for a typing mistake that does not exist. 422 says "I understood you perfectly,
     * and the answer is no". Audit item M-3 is exactly this split; the controller tests
     * assert the 422 (ProjectControllerTest, AgileControllerTest, GovernanceControllerTest).
     *
     * WHY the message goes to the user untouched: it IS the explanation. The service wrote
     * a full French sentence naming the rule, and the Angular screens display it directly -
     * they read e.error.detail, which is this field (roles admin, workload, project form,
     * resources). Replace it with a generic text and the user is refused without ever
     * knowing why.
     */
    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        // Its own "type", separate from /errors/conflict, so a client can treat "rule says
        // no" and "duplicate data" differently without reading the sentence.
        pd.setType(URI.create("/errors/business-rule"));
        return pd;
    }

    /**
     * The request collides with data that already exists -> 409 Conflict.
     *
     * WHO THROWS IT: the services, when a check-before-write finds a duplicate. Real
     * examples: "Code projet deja utilise" (ProjectService), "Email deja utilise"
     * (UserCrudService), "Un role porte deja ce nom" (RoleAdminService), "L'utilisateur est
     * deja membre de ce projet" (TeamAssignmentService), "Un snapshot existe deja pour ce
     * projet a la date d'aujourd'hui" (KpiService).
     *
     * WHY the plain IllegalArgumentException of the JDK was kept for this, instead of a
     * second custom class: audit item M-3 only needed to take the BUSINESS refusals out of
     * the 409 bucket. What stayed behind is the family "this value is already taken", and
     * 409 is the status made for it. The pair to remember is:
     *   IllegalArgumentException -> 409, the value already exists;
     *   BusinessRuleException    -> 422, the value is fine but the rule says no.
     *
     * WHY 409 and not 400: the request is correct. It was correct a second ago and it would
     * be correct again if the other row disappeared. The problem is the current state of
     * the database, not the form the user filled in.
     *
     * CAREFUL - the known limit of this handler, and a fair jury question: it catches EVERY
     * IllegalArgumentException, not only the ones the services throw on purpose. If the JDK
     * or a library raises one for a programming mistake (Enum.valueOf on an unknown name,
     * for instance), the caller receives a 409 carrying that technical message instead of a
     * 500. The honest answer is that the price was accepted to keep the services free of a
     * second custom exception class; a stricter version would use a dedicated
     * DuplicateException here and let anything else fall through to handleGeneric.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        // Same "type" as handleDataIntegrity below: whether the duplicate is caught by our
        // own check or by a PostgreSQL unique index, the client sees the same kind of
        // error, and that is on purpose (see C-4).
        pd.setType(URI.create("/errors/conflict"));
        return pd;
    }

    /**
     * The caller is identified but is not allowed to do this -> 403 Forbidden.
     * This is the handler that makes the whole authorization model of the project visible
     * to the outside world, so expect a jury question on it.
     *
     * WHO THROWS IT - three different guards, all ending in the same 403:
     *   1. @PreAuthorize("hasAuthority('X')") placed on SERVICE methods. The check is on a
     *      PERMISSION, never on the name of a role: the roles are rows in the database and
     *      an administrator can change what a role carries without touching the code.
     *      Spring Security raises AccessDeniedException when the authority is missing.
     *   2. ProjectScopeService.assertCanAccess, called by ProjectScopeInterceptor on every
     *      /api/projects/{id}/** URL - ADR-021. Having the permission is NOT enough: the
     *      project must also be inside your perimeter. Without this second guard, editing
     *      the number in the address bar (/api/projects/4 -> /api/projects/9) would open
     *      somebody else's project; the attack is called IDOR.
     *   3. Ownership guards inside the services, such as "a developer can only act on his
     *      own workload entries" (ChargeReelleService, rule BR-033).
     *
     * WHY this handler must exist at all, although Spring Security already knows how to
     * answer 403: the exception escapes from a service called by a controller, so Spring
     * MVC looks for an @ExceptionHandler first. The catch-all handleGeneric(Exception) at
     * the bottom of this file would match it and answer 500 "Erreur interne du serveur".
     * Delete these few lines and every refused permission in the application becomes a
     * fake server error - and the frontend stops being able to show "not allowed".
     *
     * WHY the caller is never anonymous here: SecurityConfig declares
     * anyRequest().authenticated(), so a request without a valid token is stopped much
     * earlier by the authenticationEntryPoint with a 401. Reaching this method means "we
     * know who you are".
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        // A fixed sentence, and NOT ex.getMessage(): this is the only handler of the file
        // that throws the exception message away on purpose. The internal messages are
        // precise ("Projet hors perimetre", "Un developpeur ne peut agir que sur ses
        // propres charges"), and each of them tells the caller which guard stopped him -
        // that is, a piece of the map of how the authorization is built, and whether the
        // row he asked for exists at all. Sending back one
        // neutral "Acces refuse" keeps that map private. The cost is real and accepted:
        // this handler does not log the dropped message either, so when a user complains
        // about a 403 the exact guard that fired has to be found by reading the code.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Accès refusé");
        pd.setType(URI.create("/errors/forbidden"));
        return pd;
    }

    /**
     * A value in the URL or in a query parameter has the wrong type -> 400 Bad Request.
     *
     * WHO THROWS IT: Spring, while it converts the text of the URL into the java type the
     * controller method asks for. GET /api/projects/abc when the method declares a
     * PathVariable of type Long, or ?page=deux when it declares an int, or an unknown
     * value for a parameter typed as an enum.
     *
     * WHY it is needed: this failure happens BEFORE the controller body runs, so no service
     * and no validation annotation can catch it. Without this handler the conversion error
     * would fall to handleGeneric and the user would get 500 "internal error" for a simple
     * typing mistake in the address bar - and the server log would fill with ERROR lines
     * that are not server problems at all.
     *
     * WHY the parameter name and the bad value are sent back: they come from the request
     * the caller wrote himself, so he learns nothing new, and it is the only way for him to
     * see which parameter to fix.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // getName() = the name of the parameter as the controller declares it (id, page,
        // statut...); getValue() = the raw text that could not be converted.
        // getValue() may be null; writing it with "+" is safe, java simply prints "null"
        // rather than throwing a NullPointerException inside the error handler itself -
        // which would be the worst possible place to crash.
        String detail = "Valeur invalide pour le paramètre '" + ex.getName() + "' : " + ex.getValue();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create("/errors/validation"));
        return pd;
    }

    /**
     * The URL matches no controller at all -> 404 Not Found.
     *
     * WHO THROWS IT: the Spring DispatcherServlet, when it finds no method to call for the
     * requested path (a typing mistake such as /api/projet/1, or an old endpoint that no
     * longer exists).
     *
     * WHY IT ONLY WORKS BECAUSE OF TWO LINES IN application.yml:
     *   spring.mvc.throw-exception-if-no-handler-found: true  -> asks Spring to THROW here
     *       instead of quietly answering with its own default error page;
     *   spring.web.resources.add-mappings: false              -> switches off the static
     *       file handler, which otherwise matches every unknown URL (it is looking for a
     *       file to serve) and so never lets the exception happen.
     * Remove either line and this handler becomes dead code: unknown URLs answer with
     * Spring Boot's generic HTML/JSON error instead of the /errors/not-found contract that
     * the rest of the application respects. This is a backend that serves only JSON, so
     * losing the static handler costs nothing.
     *
     * WHY TWO METHODS ARE BOTH NAMED handleNotFound: java allows two methods with the same
     * name when their parameters differ (this is called overloading), and Spring chooses by
     * exception type, not by name. Having the same name is a way of saying "these two very
     * different failures - unknown row, unknown route - must look identical to the client".
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ProblemDetail handleNotFound(NoHandlerFoundException ex) {
        // getRequestURL() is the path the caller himself typed, so echoing it back reveals
        // nothing and is the fastest way for a developer to spot the typing mistake.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Route introuvable : " + ex.getRequestURL());
        pd.setType(URI.create("/errors/not-found"));
        return pd;
    }

    /**
     * PostgreSQL itself refused the write -> 409 Conflict. Audit item C-4.
     *
     * WHO THROWS IT: Spring Data, when the database rejects an INSERT or an UPDATE because
     * of a constraint: a unique index, a foreign key, a NOT NULL column, or a CHECK such as
     * chk_project_dates. Nobody in the application throws it on purpose.
     *
     * WHY IT EXISTS - the two real cases behind C-4:
     *   1. A rule written in the database but not in the java code. The original CHECK
     *      demanded end_date > start_date strictly, while the application accepted a
     *      one-day project (start = end, duration counted inclusively as in the Excel
     *      model). Creating such a project raised this exception, no handler existed, and
     *      the user got a 500 for a perfectly normal input. The CHECK itself was fixed by
     *      migration V17; this handler is the safety net for the whole family of cases.
     *   2. A race between two users. The services test a duplicate with existsByCode(...)
     *      and then save. Those are two separate statements: two people can pass the test
     *      at the same moment, and the second INSERT is then stopped by the unique index.
     *      Without this handler that perfectly normal race answers 500.
     *
     * WHY 409 and the same /errors/conflict "type" as handleIllegalArgument: from outside,
     * "I checked and this code is taken" and "the database says this code is taken" are the
     * same event. The frontend must not have to care which layer noticed.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        // getMostSpecificCause() digs through the chain of wrapped exceptions down to the
        // real PostgreSQL error, the one that names the constraint
        // ("uk_projects_code", "chk_project_dates"). That name is what a developer needs.
        // It is logged at WARN and NOT returned to the caller, because the names of the
        // tables, columns and constraints describe the internal shape of the database.
        // Passing the throwable as the last argument makes slf4j print its stack trace.
        // WARN and not ERROR: the application is healthy, somebody simply hit a rule.
        log.warn("Contrainte de données violée", ex.getMostSpecificCause());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Opération impossible : contrainte de données (doublon ou valeur invalide)");
        pd.setType(URI.create("/errors/conflict"));
        return pd;
    }

    /**
     * Too many failed logins for this email -> 429 Too Many Requests. Audit item H-2.
     *
     * WHO THROWS IT: AuthService.login() only, on its very first line, when
     * LoginAttemptTracker has already recorded 5 failures for that email inside the last
     * 15 minutes. The password is not even checked, and bcrypt is not run.
     *
     * WHY a status of its own instead of another 401: it changes what the user must DO.
     * 401 means "try again", 429 means "stop and wait". login.component.ts branches on the
     * status and shows the waiting message (audit item N-3); without the split it would
     * keep telling a locked-out user that his password is wrong, and he would keep trying,
     * which only pushes the end of the window further away.
     *
     * WHY the delay is inside the message: the sentence built by AuthService is computed
     * from LoginAttemptTracker.WINDOW_SECONDS, so it can never announce a delay different
     * from the one really enforced.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail handleTooManyRequests(TooManyRequestsException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        pd.setType(URI.create("/errors/too-many-requests"));
        return pd;
    }

    /**
     * The last resort: anything not matched by the twelve handlers above -> 500.
     *
     * WHAT IT CATCHES: everything that was not foreseen. A NullPointerException, a database
     * that went down, an arithmetic error in a computation, and also a few Spring MVC
     * failures for which this class declares no handler (a malformed JSON body, an HTTP
     * method that the endpoint does not offer).
     *
     * WHY THE CLIENT GETS NOTHING: the body says only "Erreur interne du serveur". An
     * unexpected exception message often contains a SQL statement, a file path, a class
     * name or a column name, and each of them tells an attacker how the application is
     * built. The opposite choice - returning the raw message - is the classic way a small
     * bug becomes the first step of an intrusion.
     *
     * WHY log.error WITH the exception object: the stack trace is the only thing left to
     * understand the problem, since the client was told nothing. ERROR, not WARN, is right
     * here: unlike the handlers above, reaching this method means the application really
     * has a defect, and these lines are the ones to watch in production.
     *
     * WHY THIS HANDLER DOES NOT SWALLOW THE OTHERS: Spring always picks the closest
     * matching exception type, and Exception is the furthest parent of them all, so this
     * method only runs when nothing more precise matched. That also explains why the
     * AccessDeniedException handler above is not optional: without it, every refused
     * permission would end up in this 500.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Erreur inattendue", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne du serveur");
        pd.setType(URI.create("/errors/internal"));
        return pd;
    }
}

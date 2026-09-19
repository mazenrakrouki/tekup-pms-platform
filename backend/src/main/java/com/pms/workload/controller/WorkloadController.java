package com.pms.workload.controller;

import com.pms.workload.dto.ChargeReelleRequest;
import com.pms.workload.dto.ChargeReelleResponse;
import com.pms.workload.dto.PlanChargeRequest;
import com.pms.workload.dto.PlanChargeResponse;
import com.pms.workload.service.ChargeReelleService;
import com.pms.workload.service.PlanChargeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * WHAT THIS FILE IS
 * The REST door to the workload of one project. It carries two families of rows that look
 * alike but do not mean the same thing: the PLANNED workload (plan de charge - how many days
 * a member is expected to spend on the project during one month) and the ACTUAL workload
 * (charge reelle - how many days he declares he really spent, which the project manager then
 * validates).
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular WorkloadService (listPlanCharges, createPlanCharge, listChargesReelles,
 *   submitCharge, validateCharge), used by workload.component.ts and by the project detail
 *   screen
 *     -> HTTP call on /api/projects/{projectId}/plan-charges or .../charges-reelles
 *     -> JwtAuthenticationFilter reads the short-lived access token and puts the e-mail of
 *        the caller, together with his permission codes, in the security context
 *     -> ProjectScopeInterceptor (ADR-021) reads the {projectId} written in the URL and
 *        answers 403 when that project is outside the perimeter of the caller, before any
 *        method of this class is entered
 *     -> this controller: turns the URL, the paging parameters and the JSON body into a
 *        method call, and picks the HTTP status code of the answer
 *     -> PlanChargeService / ChargeReelleService: permission check (@PreAuthorize sits on
 *        the service method), business rules, and narrowing of the reading to the rows of
 *        the caller himself when he holds no wide-view capability (BR-062...064)
 *     -> PlanChargeRepository / ChargeReelleRepository, then the MapStruct mappers
 *     -> PlanChargeResponse / ChargeReelleResponse, written back as JSON.
 *
 * WHY IT EXISTS
 * Delete this file and the two workload screens have no server to talk to: nobody can plan a
 * month, nobody can declare the days he worked, nobody can validate them. Everything built on
 * top of those numbers falls with it - the "planned against real" follow-up and the KPI that
 * compare the two. The planned rows are the only place in the application where the expected
 * effort per person and per month is recorded.
 *
 * WHY THE TWO FAMILIES SHARE ONE CLASS
 * They are two tables, two services and different permissions, but for the user they are one
 * screen: he reads the planned month and the real month side by side. Keeping both URL
 * families in one controller keeps them visible together and keeps them under one heading in
 * the generated API page.
 *
 * WHAT IT DELIBERATELY DOES NOT CONTAIN
 * No permission test, no business rule, no calculation. Authorization is dynamic and
 * permission-based: the hasAuthority checks sit on the service methods, never here, and the
 * code never tests the NAME of a role. Three capabilities are involved:
 *   VIEW_WORKLOAD     -> read the two lists;
 *   SUBMIT_WORKLOAD   -> declare or correct an actual workload (a developer holds it);
 *   VALIDATE_WORKLOAD -> write the plan, and validate or delete an actual workload (the
 *                        project manager holds it).
 * Moving one of them to another role is one row added in role_permission; this file does not
 * change.
 *
 * PERMISSION AND PERIMETER ARE TWO DIFFERENT QUESTIONS (ADR-021)
 * The permission answers "may this person do this kind of action at all?". The perimeter
 * answers "on which project?". A project manager really does hold VALIDATE_WORKLOAD, so the
 * permission alone would let him send POST /api/projects/9/plan-charges and plan the team of
 * a colleague. ProjectScopeInterceptor reads the 9, sees that project 9 is not his, and
 * answers 403 before this class runs. That is why every URL below carries the project number
 * inside the path: a URL shaped like /api/workload?projectId=9 would carry the same
 * information, would not match the ADR-021 pattern, and the perimeter would silently stop
 * being enforced.
 *
 * A THIRD AND FINER FILTER, INSIDE THE SERVICES (BR-062...064)
 * Being allowed on the project does not mean seeing everybody on it. A developer who opens
 * the screen gets his own rows only; the wide view is kept for the holders of
 * VALIDATE_WORKLOAD (the project manager, who plans and validates) and of VIEW_ALL_PROJECTS
 * (the director). Two users can therefore call the very same URL and receive a different
 * number of rows. That choice lives in the services and not here, so that it still applies
 * when a service is called from somewhere other than an HTTP request.
 *
 * ONE SHAPE COMES BACK IN EVERY METHOD: year + month
 * The JSON always carries year and month as two separate numbers, while the database keeps
 * one date column placed on the first day of that month (period = 2026-03-01 means March
 * 2026). The services build that date, the mappers split it back into year and month. Why a
 * real date rather than two integer columns: a date sorts and compares directly in SQL ("from
 * March to June") and supports one unique row per project, per user and per month, which two
 * loose numbers would make clumsy.
 *
 * WHAT THE CALLER GETS WHEN SOMETHING IS REFUSED
 * No method below writes an error itself. The services throw, and GlobalExceptionHandler
 * turns each exception into a code:
 *   400 a field of the body is missing or out of range (bean validation, see @Valid),
 *   401 no valid access token,
 *   403 project outside the perimeter, or a developer touching the row of somebody else,
 *   404 unknown project, unknown row, or row that belongs to another project,
 *   409 a row already exists for this user and this month,
 *   422 a rule says no (period changed, user changed, row already validated).
 */
// @Tag is documentation only: springdoc reads it and groups the nine endpoints of this class
// under one heading, "Charges de travail", in the generated OpenAPI / Swagger page. Nothing
// changes at run time.
// Why it is needed: without it the API page lists these URLs under an automatic name such as
// "workload-controller", lost among the other groups that share the /api/projects/... prefix
// (Projets, Sprints, Backlog, KPI...), and a reader cannot tell them apart. The text is in
// French because every @Tag of the backend is written in French, so the generated page stays
// in one single language from end to end.
@Tag(name = "Charges de travail", description = "Plan de charge mensuel + charges réelles (soumettre / valider)")
// @RestController = @Controller + @ResponseBody. Spring routes the HTTP requests to this class
// and writes every returned object straight into the answer as JSON.
// Why: with a plain @Controller, Spring would read the returned value as the NAME of an HTML
// page to render, look for a template file and fail, instead of sending the JSON that the
// Angular application expects.
@RestController
// Lombok writes, at compile time, the constructor that takes every final field (the two
// services below). Spring uses that constructor to inject them.
// Why constructor injection rather than @Autowired on the fields: the fields stay final, so
// nothing can swap a service after start-up, and a unit test can build this controller with
// two fake services in one line, without starting Spring at all.
@RequiredArgsConstructor
// Note that there is no class-level @RequestMapping here: each method spells its full path.
// Why it is written that way: the class serves two different resource families
// (/plan-charges and /charges-reelles) that share only the /api/projects/{projectId} part, so
// a single common prefix would cover almost nothing. The price to pay is that the project id
// must be repeated in every path - and it must be, because that is what makes the ADR-021
// scope check fire.
public class WorkloadController {

    // The planned side. Every decision about it lives on the other side of this field:
    // VALIDATE_WORKLOAD is required to write, one single row per user and per month, the
    // month and the person of an existing row can no longer be changed, and the targeted user
    // must be an active member of the project team (H-8).
    private final PlanChargeService planChargeService;

    // The actual side. It carries the rules the planned side does not have: a developer may
    // act on his own rows only (BR-033), a validated row can no longer be changed or deleted,
    // and validating records who validated and when.
    private final ChargeReelleService chargeReelleService;

    // ---- Planned workload: the days a member is expected to spend, month by month ----

    /**
     * GET /api/projects/{projectId}/plan-charges - one page of the planned months of this
     * project, as a JSON object holding content, totalElements, totalPages and number. Always
     * 200 OK, with an empty content array when there is nothing to show.
     *
     * Who sees what: a holder of VALIDATE_WORKLOAD or of VIEW_ALL_PROJECTS receives the whole
     * team, anybody else receives his own rows only (BR-062...064). Soft-deleted rows never
     * come back, because every repository query here ends with "deleted = false".
     *
     * Why a page and not a plain list: a project running two years with ten people already
     * means two hundred and forty rows, and the screen shows a table with paging. The service
     * also owns a plain-list version of this same reading; this controller deliberately calls
     * the paged one, so that a screen can never pull the whole history in one answer.
     */
    // @PathVariable copies the number written in the URL into the parameter; the name
    // projectId matches the {projectId} placeholder of the mapping.
    // Why it is needed: without it the parameter stays null, the service looks for a project
    // with id null, and a perfectly valid URL answers 404.
    // @PageableDefault supplies page, size and sort when the caller sends none; Spring reads
    // ?page=&size=&sort= from the URL and builds the Pageable object.
    // Why the sort part is the important one: findActiveByProjectIdPaged in
    // PlanChargeRepository has no ORDER BY of its own, so this sort is what becomes the ORDER
    // BY in SQL. Without it PostgreSQL is free to return the rows in whatever order suits it,
    // and that order can change between two calls: the same row would then appear on page 1
    // and again on page 2 while another row is never shown at all. DESC on period puts the
    // most recent months first, which is what the screen wants, and it matches the
    // sort=period,desc that the Angular service sends anyway.
    @GetMapping("/api/projects/{projectId}/plan-charges")
    public ResponseEntity<Page<PlanChargeResponse>> listPlanCharges(
            @PathVariable Long projectId,
            @PageableDefault(size = 20, sort = "period", direction = Sort.Direction.DESC) Pageable pageable) {
        // The service is given the project id again although the interceptor already checked
        // it: here it is used to select the rows, not to guard the door. The service also
        // loads the project first, so an unknown or deleted project answers 404 instead of an
        // empty page, which tells the user the difference between "nothing planned yet" and
        // "this project does not exist".
        return ResponseEntity.ok(planChargeService.findByProject(projectId, pageable));
    }

    /**
     * POST /api/projects/{projectId}/plan-charges - plans one member for one month. Answers
     * 201 Created with the saved row (generated id included) in the body and its URL in the
     * Location header.
     *
     * Needs VALIDATE_WORKLOAD, which is the capability of the project manager: planning the
     * team is his job, not the job of the member himself.
     * Refusals that come from the service: 404 unknown project or unknown user, 422 the
     * targeted user is not an active member of the team (H-8), 409 this user is already
     * planned for this month.
     *
     * Why 201 plus a Location header rather than a bare 200: the caller learns where the new
     * row lives without building the URL itself, which is what the REST contract asks for a
     * creation. The body is sent back as well so the screen can display the row at once,
     * with its id, without a second call.
     */
    // @Valid switches on bean validation for the body before the method runs: PlanChargeRequest
    // asks for a userId, a year between 2000 and 2100, a month between 1 and 12 and a strictly
    // positive number of days capped at 31. A failure answers 400 with the list of fields.
    // Why it is needed: without @Valid those annotations are simply ignored. A body with
    // month = 13 would reach the service, LocalDate.of(year, 13, 1) would throw, and the user
    // would get a bare 500 instead of a clear "month must be at most 12".
    // @RequestBody tells Spring to read the JSON body and build the record from it (instead of
    // looking for those values in the query string, which is what it does without it).
    @PostMapping("/api/projects/{projectId}/plan-charges")
    public ResponseEntity<PlanChargeResponse> createPlanCharge(@PathVariable Long projectId,
                                                                @Valid @RequestBody PlanChargeRequest request) {
        PlanChargeResponse created = planChargeService.create(projectId, request);
        // Builds the URL of the row that was just created by taking the URL of the current
        // request (.../plan-charges) and adding the new id: .../plan-charges/42.
        // Why building it from the current request rather than writing the string by hand: the
        // host, the port and the context path are whatever the caller really used, so the same
        // code gives a correct Location behind localhost, behind Docker and behind a reverse
        // proxy. Hard-coding "http://localhost:8080/..." would send the browser to the wrong
        // machine as soon as the application is deployed.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/plan-charges/{id} - changes an existing planned row.
     * 200 OK with the updated row.
     *
     * In practice only the number of planned days can move: the service refuses with 422 if
     * the body carries another month or another user than the stored row, and answers 404 if
     * the row belongs to a different project than the one in the URL.
     * Why forbid changing the month or the person instead of simply accepting it: such a
     * change is not an edit, it is a different plan line. Allowing it would let one row jump
     * over the "one row per user and per month" rule and create a hidden duplicate.
     *
     * Note that the current Angular client does not call this endpoint (the workload screen
     * creates and lists); it is part of the REST contract and is exercised by the API tests.
     */
    // Two @PathVariable on the same method: projectId is what the ADR-021 interceptor reads,
    // id is the row to update. The service compares them and answers 404 when the row does not
    // belong to that project.
    // Why that extra comparison matters: without it, a project manager inside his own project
    // could send PUT /api/projects/3/plan-charges/77 where row 77 belongs to project 9, and
    // rewrite a row of a project he is not allowed to touch. The interceptor cannot catch it,
    // because the URL it inspects only shows project 3.
    @PutMapping("/api/projects/{projectId}/plan-charges/{id}")
    public ResponseEntity<PlanChargeResponse> updatePlanCharge(@PathVariable Long projectId,
                                                                @PathVariable Long id,
                                                                @Valid @RequestBody PlanChargeRequest request) {
        return ResponseEntity.ok(planChargeService.update(projectId, id, request));
    }

    /**
     * DELETE /api/projects/{projectId}/plan-charges/{id} - removes a planned row and answers
     * 204 No Content, with an empty body. Needs VALIDATE_WORKLOAD.
     *
     * "Removes" means soft delete: the service sets the deleted flag to true and saves, the
     * SQL row stays. Why: the planned effort of past months is history, it feeds the follow-up
     * and the KPI, and a real DELETE would also break the foreign keys of anything pointing at
     * it. The reading queries all filter on deleted = false, so the row disappears from the
     * screens all the same.
     * One consequence to be aware of: because the duplicate check also ignores deleted rows,
     * the same user and the same month can be planned again after a delete, which is exactly
     * what a correction needs.
     */
    // ResponseEntity<Void> plus noContent(): 204 states "it worked, and there is nothing to
    // read", which is the honest answer for a delete.
    // Why not 200 with a body: there is no object left to describe. Why not answer the deleted
    // row: the client would have to guess whether it still exists. 204 removes the doubt.
    @DeleteMapping("/api/projects/{projectId}/plan-charges/{id}")
    public ResponseEntity<Void> deletePlanCharge(@PathVariable Long projectId, @PathVariable Long id) {
        planChargeService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    // ---- Actual workload: the days really spent, declared then validated ----

    /**
     * GET /api/projects/{projectId}/charges-reelles - one page of the declared months of this
     * project. Same shape and same rules as the planned list above: 200 OK, VIEW_WORKLOAD,
     * and the wide view only for VALIDATE_WORKLOAD or VIEW_ALL_PROJECTS (BR-062...064).
     *
     * The rows carry more than the planned ones: submittedAt (when the member declared),
     * validatedAt and the name of the validator when the project manager has accepted them. A
     * row with validatedAt still empty is a declaration waiting for a decision, and that is
     * what the validation screen lists.
     */
    // Same @PageableDefault as the planned list, and for the same reason: the paged query of
    // ChargeReelleRepository carries no ORDER BY, so without this sort the paging would not be
    // stable and a row could be shown twice or never. Keeping the two lists on the same
    // default (period, newest first, twenty per page) also means the two tables of the screen
    // scroll the same way.
    @GetMapping("/api/projects/{projectId}/charges-reelles")
    public ResponseEntity<Page<ChargeReelleResponse>> listChargesReelles(
            @PathVariable Long projectId,
            @PageableDefault(size = 20, sort = "period", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(chargeReelleService.findByProject(projectId, pageable));
    }

    /**
     * POST /api/projects/{projectId}/charges-reelles - a member declares the days he really
     * spent on this project during one month. 201 Created, with the saved row in the body and
     * its URL in the Location header. The service stamps submittedAt with the current time.
     *
     * Needs SUBMIT_WORKLOAD, not VALIDATE_WORKLOAD: declaring is the job of the member,
     * accepting is the job of the project manager, and the two must stay separate so that
     * nobody validates his own declaration by accident.
     * Refusals that come from the service: 403 a developer who tries to declare for somebody
     * else (BR-033 - only a holder of VALIDATE_WORKLOAD may submit for a third party), 422 the
     * targeted user is not an active member of the team (H-8), 409 a declaration already
     * exists for this user and this month, 404 unknown project or unknown user.
     */
    // @Valid again, on ChargeReelleRequest this time. One difference with the planned request
    // is worth knowing at the jury: actualDays is @PositiveOrZero while plannedDays is
    // @Positive. Why: planning somebody for zero day means nothing, but declaring zero day is a
    // real answer - the member was planned on the project and finally worked on something else.
    // Both are capped at 31, because a month cannot hold more days than that.
    @PostMapping("/api/projects/{projectId}/charges-reelles")
    public ResponseEntity<ChargeReelleResponse> submitCharge(@PathVariable Long projectId,
                                                              @Valid @RequestBody ChargeReelleRequest request) {
        ChargeReelleResponse created = chargeReelleService.submit(projectId, request);
        // Same Location as the creation of a planned row: the URL of the current request plus
        // the id that the database has just generated, so the client never guesses it.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/charges-reelles/{id} - the member corrects a declaration
     * he already sent. 200 OK with the updated row, and submittedAt stamped again with the
     * current time, because the declaration that counts is the last one.
     *
     * Needs SUBMIT_WORKLOAD, and the service applies BR-033 once more: without
     * VALIDATE_WORKLOAD the caller can only touch his own row, otherwise 403.
     * The service refuses with 422 when the row is already validated. Why that guard is the
     * important one here: a validated declaration has been accepted by the project manager and
     * feeds the follow-up figures. If it could still be rewritten afterwards, the numbers the
     * manager approved and the numbers stored would quietly stop being the same.
     *
     * Note that the current Angular client does not call this endpoint either; the screen
     * declares and validates.
     */
    // Same two @PathVariable and the same reason as the planned update: the service checks that
    // row {id} really belongs to project {projectId} and answers 404 otherwise, which closes
    // the hole the ADR-021 interceptor cannot see (it only reads the project number in the URL,
    // not the project the row points at).
    @PutMapping("/api/projects/{projectId}/charges-reelles/{id}")
    public ResponseEntity<ChargeReelleResponse> updateCharge(@PathVariable Long projectId,
                                                              @PathVariable Long id,
                                                              @Valid @RequestBody ChargeReelleRequest request) {
        return ResponseEntity.ok(chargeReelleService.update(projectId, id, request));
    }

    /**
     * PATCH /api/projects/{projectId}/charges-reelles/{id}/validate - the project manager
     * accepts a declaration. 200 OK with the row, now carrying validatedAt and the validator.
     * Needs VALIDATE_WORKLOAD. Answers 422 when the row is already validated, so clicking
     * twice cannot rewrite the date and the name of the first validator.
     *
     * Why PATCH and not PUT: the caller sends no state at all, he asks for one small change on
     * an existing row. PUT means "here is the full new content of the resource", which is not
     * what happens here.
     * Why a separate /validate URL instead of a "validated: true" field inside the normal
     * update: the two actions do not need the same capability. Submitting needs
     * SUBMIT_WORKLOAD, validating needs VALIDATE_WORKLOAD. Two URLs means two service methods,
     * so each one can carry its own @PreAuthorize. Mixed into one endpoint, a developer holding
     * SUBMIT_WORKLOAD could send the flag himself and validate his own days.
     */
    // @AuthenticationPrincipal hands over the principal that JwtAuthenticationFilter placed in
    // the security context, which in this application is the e-mail of the logged-in user as a
    // plain String. The service turns it into the User row it writes in validatedBy.
    // Why read the identity from the token instead of taking a validatorEmail field in the
    // body: the body is written by the client and can say anything, while the e-mail in the
    // token was signed by the server and verified on this very request. Otherwise the audit
    // trail "validated by X on this date" could name somebody who never clicked anything.
    @PatchMapping("/api/projects/{projectId}/charges-reelles/{id}/validate")
    public ResponseEntity<ChargeReelleResponse> validateCharge(@PathVariable Long projectId,
                                                                @PathVariable Long id,
                                                                @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(chargeReelleService.validate(projectId, id, email));
    }

    /**
     * DELETE /api/projects/{projectId}/charges-reelles/{id} - drops a declaration. 204 No
     * Content. Needs VALIDATE_WORKLOAD: a member cannot make his own declaration disappear,
     * only the project manager can.
     *
     * Soft delete again (the deleted flag is raised, the SQL row stays), and one guard more
     * than on the planned side: the service answers 422 for a row that is already validated.
     * Why: removing an accepted declaration would change the real workload of a closed month
     * without leaving any trace. The manager must live with what he validated.
     */
    @DeleteMapping("/api/projects/{projectId}/charges-reelles/{id}")
    public ResponseEntity<Void> deleteCharge(@PathVariable Long projectId, @PathVariable Long id) {
        chargeReelleService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}

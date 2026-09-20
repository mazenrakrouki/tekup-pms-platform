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
 * REST door to one project's workload: PLANNED (plan de charge — expected days per member
 * per month) and ACTUAL (charge reelle — declared days, validated by the project manager).
 * ProjectScopeInterceptor (ADR-021) enforces the project perimeter from the {projectId} in
 * the URL before any method here runs, which is why every path below repeats it rather than
 * taking it as a query parameter.
 *
 * <p>Contains no permission test, no business rule, no calculation — those live on
 * PlanChargeService/ChargeReelleService, guarded by permission (never role name): VIEW_WORKLOAD
 * to read, SUBMIT_WORKLOAD to declare/correct an actual workload, VALIDATE_WORKLOAD to write
 * the plan or validate/delete an actual workload. The services also apply a finer,
 * per-caller filter (BR-062...064): a developer sees only their own rows, while
 * VALIDATE_WORKLOAD/VIEW_ALL_PROJECTS holders see everyone's.
 *
 * <p>Every method exchanges year + month as two numbers over JSON, while the database keeps
 * one date column (period, first-of-month) — the services build it, the mappers split it back.
 *
 * <p>Errors are thrown by the services and mapped by GlobalExceptionHandler: 400 invalid
 * body (@Valid), 401 no valid token, 403 outside perimeter or another user's row, 404 unknown
 * project/row, 409 duplicate row for user+month, 422 a business rule refuses (period/user
 * changed, already validated).
 */
// @Tag groups these nine endpoints under "Charges de travail" in the generated OpenAPI page,
// distinguishing them from the other groups sharing the /api/projects/... prefix.
@Tag(name = "Charges de travail", description = "Plan de charge mensuel + charges réelles (soumettre / valider)")
@RestController
@RequiredArgsConstructor
// No class-level @RequestMapping: the two resource families (/plan-charges,
// /charges-reelles) share only /api/projects/{projectId}, and that id must appear in every
// path for the ADR-021 scope check to fire.
public class WorkloadController {

    // Planned side: VALIDATE_WORKLOAD to write, one row per user per month, month/person of
    // an existing row are immutable, and the targeted user must be an active team member (H-8).
    private final PlanChargeService planChargeService;

    // Actual side: a developer may act on their own rows only (BR-033), a validated row is
    // frozen, and validation records who validated and when.
    private final ChargeReelleService chargeReelleService;

    // ---- Planned workload: the days a member is expected to spend, month by month ----

    /**
     * GET /api/projects/{projectId}/plan-charges - one page of the planned months of this
     * project. Always 200 OK, empty content array when there's nothing to show.
     *
     * <p>VALIDATE_WORKLOAD/VIEW_ALL_PROJECTS holders see the whole team, anybody else their
     * own rows only (BR-062...064). Paged rather than a plain list so a screen can never pull
     * the whole multi-year history in one answer.
     */
    // @PageableDefault's sort matters because PlanChargeRepository's paged query has no
    // ORDER BY of its own — without it, rows could shuffle between pages on reload. DESC on
    // period matches what the Angular service requests anyway.
    @GetMapping("/api/projects/{projectId}/plan-charges")
    public ResponseEntity<Page<PlanChargeResponse>> listPlanCharges(
            @PathVariable Long projectId,
            @PageableDefault(size = 20, sort = "period", direction = Sort.Direction.DESC) Pageable pageable) {
        // The service loads the project first, so an unknown/deleted project answers 404
        // instead of an ambiguous empty page.
        return ResponseEntity.ok(planChargeService.findByProject(projectId, pageable));
    }

    /**
     * POST /api/projects/{projectId}/plan-charges - plans one member for one month. 201
     * Created with the saved row and its URL in the Location header.
     *
     * <p>Needs VALIDATE_WORKLOAD: planning the team is the project manager's job, not the
     * member's own. Refusals from the service: 404 unknown project/user, 422 not an active
     * team member (H-8), 409 already planned for this month.
     */
    // @Valid triggers PlanChargeRequest's bean validation (userId, year 2000-2100, month
    // 1-12, positive days capped at 31) before the method runs — without it a month=13 would
    // reach LocalDate.of() and crash with a bare 500 instead of a clear 400.
    @PostMapping("/api/projects/{projectId}/plan-charges")
    public ResponseEntity<PlanChargeResponse> createPlanCharge(@PathVariable Long projectId,
                                                                @Valid @RequestBody PlanChargeRequest request) {
        PlanChargeResponse created = planChargeService.create(projectId, request);
        // Built from the current request rather than hard-coded, so Location is correct
        // behind localhost, Docker or a reverse proxy alike.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/plan-charges/{id} - changes an existing planned row. 200
     * OK with the updated row.
     *
     * <p>Only the number of planned days can actually move: the service refuses with 422 if
     * the body carries a different month or user than the stored row (that would be a
     * different plan line, not an edit, and could dodge the "one row per user/month" rule),
     * and 404 if the row belongs to a different project than the URL.
     *
     * <p>Not called by the current Angular client (which creates and lists only); exercised
     * by the API tests as part of the REST contract.
     */
    // The service compares {id}'s actual project against {projectId} and 404s on a mismatch —
    // closing a hole ProjectScopeInterceptor can't see, since it only reads {projectId} itself.
    @PutMapping("/api/projects/{projectId}/plan-charges/{id}")
    public ResponseEntity<PlanChargeResponse> updatePlanCharge(@PathVariable Long projectId,
                                                                @PathVariable Long id,
                                                                @Valid @RequestBody PlanChargeRequest request) {
        return ResponseEntity.ok(planChargeService.update(projectId, id, request));
    }

    /**
     * DELETE /api/projects/{projectId}/plan-charges/{id} - removes a planned row. 204 No
     * Content. Needs VALIDATE_WORKLOAD.
     *
     * <p>Soft delete: the row stays with deleted=true, since past planned effort feeds the
     * KPI history. Consequence: since the duplicate check also ignores deleted rows, the
     * same user/month can be planned again right after — exactly what a correction needs.
     */
    @DeleteMapping("/api/projects/{projectId}/plan-charges/{id}")
    public ResponseEntity<Void> deletePlanCharge(@PathVariable Long projectId, @PathVariable Long id) {
        planChargeService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    // ---- Actual workload: the days really spent, declared then validated ----

    /**
     * GET /api/projects/{projectId}/charges-reelles - one page of the declared months of this
     * project. Same shape and rules as the planned list above.
     *
     * <p>Rows also carry submittedAt, and validatedAt + validator once the project manager
     * has accepted them; validatedAt still empty means a declaration awaiting decision.
     */
    // Same @PageableDefault and reason as the planned list: ChargeReelleRepository's paged
    // query has no ORDER BY of its own.
    @GetMapping("/api/projects/{projectId}/charges-reelles")
    public ResponseEntity<Page<ChargeReelleResponse>> listChargesReelles(
            @PathVariable Long projectId,
            @PageableDefault(size = 20, sort = "period", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(chargeReelleService.findByProject(projectId, pageable));
    }

    /**
     * POST /api/projects/{projectId}/charges-reelles - a member declares days really spent
     * during one month. 201 Created; the service stamps submittedAt with the current time.
     *
     * <p>Needs SUBMIT_WORKLOAD, not VALIDATE_WORKLOAD, so declaring (member) and accepting
     * (project manager) stay separate roles. Refusals: 403 a developer declaring for someone
     * else (BR-033), 422 not an active team member (H-8), 409 duplicate for user+month, 404
     * unknown project/user.
     */
    // Unlike plannedDays (@Positive), actualDays is @PositiveOrZero: declaring zero days is a
    // real answer (planned but didn't end up working on it), while planning zero means nothing.
    @PostMapping("/api/projects/{projectId}/charges-reelles")
    public ResponseEntity<ChargeReelleResponse> submitCharge(@PathVariable Long projectId,
                                                              @Valid @RequestBody ChargeReelleRequest request) {
        ChargeReelleResponse created = chargeReelleService.submit(projectId, request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/charges-reelles/{id} - the member corrects an already-sent
     * declaration; submittedAt is stamped again since the last declaration is the one that counts.
     *
     * <p>Needs SUBMIT_WORKLOAD; BR-033 still applies (only VALIDATE_WORKLOAD may touch someone
     * else's row, otherwise 403). Refuses with 422 once the row is validated — a manager's
     * approved figures must not quietly change underneath them.
     *
     * <p>Not called by the current Angular client (which declares and validates only).
     */
    // Same reason as the planned update: the service checks {id} really belongs to
    // {projectId} and 404s otherwise, closing the hole ADR-021's interceptor can't see.
    @PutMapping("/api/projects/{projectId}/charges-reelles/{id}")
    public ResponseEntity<ChargeReelleResponse> updateCharge(@PathVariable Long projectId,
                                                              @PathVariable Long id,
                                                              @Valid @RequestBody ChargeReelleRequest request) {
        return ResponseEntity.ok(chargeReelleService.update(projectId, id, request));
    }

    /**
     * PATCH /api/projects/{projectId}/charges-reelles/{id}/validate - the project manager
     * accepts a declaration. 200 OK, row now carrying validatedAt and the validator. Needs
     * VALIDATE_WORKLOAD; 422 if already validated, so double-clicking can't overwrite the
     * first validator's name/date.
     *
     * <p>PATCH, not PUT, since no full new state is sent — one small change to an existing
     * row. A separate URL rather than a "validated: true" field in the normal update, so
     * validating can carry its own @PreAuthorize distinct from SUBMIT_WORKLOAD (otherwise a
     * developer could validate their own days).
     */
    // @AuthenticationPrincipal is the token-verified email, not a client-supplied field —
    // otherwise the "validated by X" audit trail could name someone who never clicked anything.
    @PatchMapping("/api/projects/{projectId}/charges-reelles/{id}/validate")
    public ResponseEntity<ChargeReelleResponse> validateCharge(@PathVariable Long projectId,
                                                                @PathVariable Long id,
                                                                @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(chargeReelleService.validate(projectId, id, email));
    }

    /**
     * DELETE /api/projects/{projectId}/charges-reelles/{id} - drops a declaration. 204 No
     * Content. Needs VALIDATE_WORKLOAD, not the member: only the project manager can make a
     * declaration disappear.
     *
     * <p>Soft delete; also 422 for an already-validated row — an accepted declaration must
     * not silently change the recorded workload of a closed month.
     */
    @DeleteMapping("/api/projects/{projectId}/charges-reelles/{id}")
    public ResponseEntity<Void> deleteCharge(@PathVariable Long projectId, @PathVariable Long id) {
        chargeReelleService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}

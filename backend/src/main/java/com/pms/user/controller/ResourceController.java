package com.pms.user.controller;

import com.pms.user.dto.ResourceRequest;
import com.pms.user.dto.ResourceResponse;
import com.pms.user.dto.TccAnnuelDto;
import com.pms.user.service.ResourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

// ============================================================================
// FILE: ResourceController
//
// WHAT THIS FILE IS
//   The HTTP door to the cost referential: the seven endpoints under
//   /api/resources that manage a Resource (what a person COSTS - daily rate,
//   TCC rate, staffing dates) and the per-year history of those rates.
//
// WHAT A "RESOURCE" IS - SAY THIS FIRST, IT IS ASKED EVERY TIME
//   ADR-022 splits the person in two. The User entity is the ACCOUNT: who can
//   sign in, with which role. The Resource is the COST of that same person.
//   Two entities because they do not have the same lifetime, the same owner or
//   the same sensitivity: an account is created for everybody, a rate is
//   written by Administration and read by the margin calculations.
//   TCC = "taux de coût chargé", the overhead coefficient applied on top of the
//   daily rate to get the real cost of a day of work.
//
// WHERE IT SITS IN THE FLOW
//   Angular resources.component.ts (the TCC referential screen) and
//   dashboard.component.ts
//     -> SecurityConfig: anyRequest().authenticated(), so no valid token means
//        401 before this class is reached
//     -> THIS FILE: maps URL + verb onto a service call, asks Bean Validation
//        to check the body, picks the status code. No rule of its own.
//     -> ResourceService: @PreAuthorize("hasAuthority('VIEW_RESOURCES')") on
//        the reads, @PreAuthorize("hasAuthority('MANAGE_RESOURCES')") on the
//        writes, plus the data-scope checks described below
//     -> ResourceRepository / TccAnnuelRepository / UserRepository -> tables
//        resources, tcc_annuel, users
//     -> ResourceMapper -> ResourceResponse, or TccAnnuelDto, as JSON.
//
// WHY IT EXISTS
//   Delete it and nobody can enter a rate any more. Everything downstream
//   depends on these numbers: the internal quote (Devis Interne), every margin,
//   every EVM indicator of the KPI screen. They would all compute on an empty
//   referential and silently show zero.
//
// COMPUTED AMOUNTS ARE NEVER STORED
//   ResourceResponse.annualCost is not a column. The Resource entity computes
//   it when it is read (getAnnualCost(), which ResourceMapper calls). The rule
//   across the whole application, and the reason the DI area is trusted: an
//   amount that is derived is derived at read time. A stored total would keep
//   the old value the day the daily rate is corrected, and the quote would no
//   longer add up to its own lines.
//
// THE DATA SCOPE, AND WHY IT IS NOT ADR-021's INTERCEPTOR
//   ADR-021 says ProjectScopeInterceptor enforces BOTH the permission AND the
//   project scope, but it only looks at URLs matching /api/projects/{id}/**.
//   The paths here carry a RESOURCE id, so the interceptor sees no project id
//   and lets them through. The same idea therefore had to be written by hand
//   inside ResourceService:
//     * a caller holding MANAGE_RESOURCES (Admin, Directeur) sees the whole
//       referential;
//     * a caller holding only VIEW_RESOURCES - a project manager - sees only
//       themselves and the people assigned to the projects they manage;
//       asking for anyone else by id raises AccessDeniedException -> 403.
//   Note what this means: having the permission is not enough, and the check
//   cannot be read in this file. It is in ResourceService.assertVisible().
//
// WHY THE PERMISSION CHECKS ARE NOT WRITTEN IN THIS FILE
//   @PreAuthorize sits on the SERVICE methods, never on the controller. The
//   guard belongs to the operation: KpiService and DevisInterneService reach
//   the same rates without passing through any URL, so a check written on the
//   URL would protect nothing for them.
// ============================================================================

// @Tag: springdoc/OpenAPI only. It groups these endpoints under "Ressources" in
// the Swagger page rather than under the raw class name.
//
// @RestController: Spring builds one instance at start-up, scans it for
// mappings, and writes whatever a method returns straight into the response
// body as JSON (Jackson). Without it the returned value would be taken for the
// name of an HTML view to render.
//
// @RequestMapping: the shared prefix, written once. Every mapping below is
// relative to /api/resources.
//
// @RequiredArgsConstructor: Lombok writes the constructor taking every final
// field and Spring injects through it, so the service reference can never be
// null and can never be swapped at runtime.
/**
 * Human-resource referential: rates, staffing window, and the per-year TCC
 * history (F-AFF-13 §6.3 rule 4).
 *
 * <p>The class carries no business rule on purpose. Who may read what, which
 * rows a project manager is allowed to see, and how a year of rates is replaced
 * all live in {@link ResourceService}, so they hold for every caller and not
 * only for these URLs.
 */
@Tag(name = "Ressources", description = "CRUD ressources humaines, tarifs journaliers et historique TCC annuel")
@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {

    // The only collaborator. Final, so Lombok puts it in the generated
    // constructor and Spring supplies the singleton service at start-up.
    private final ResourceService resourceService;

    // WHAT IT DOES / GIVES BACK
    //   GET /api/resources -> 200 with the resources the caller is allowed to
    //   see, sorted by last name, each one carrying its daily rate, its TCC
    //   rate, its staffing window and its computed annual cost.
    //   403 if the caller does not hold VIEW_RESOURCES.
    //
    // THE ANSWER DEPENDS ON WHO ASKS - this is the part a jury looks for.
    //   The URL is the same for everybody, but ResourceService.findAll() checks
    //   the authorities of the current user: MANAGE_RESOURCES gets the whole
    //   company referential, VIEW_RESOURCES alone gets only the people of the
    //   projects that person manages. Without that split, a project manager
    //   opening this screen would read every salary cost in the company.
    //
    // WHY THE LIST IS NOT PAGINATED, unlike GET /api/users
    //   One resource per staffed employee: a short list that the screen filters
    //   in the browser. Paging would add a page object to the contract for a
    //   few dozen rows.
    @GetMapping
    public ResponseEntity<List<ResourceResponse>> list() {
        return ResponseEntity.ok(resourceService.findAll());
    }

    // WHAT IT DOES / GIVES BACK
    //   GET /api/resources/{id} -> 200 with one resource, 404 if the id is
    //   unknown or the row is soft-deleted, 403 if the caller holds only
    //   VIEW_RESOURCES and that resource is outside the projects they manage.
    //   That last 403 comes from ResourceService.assertVisible(), not from the
    //   @PreAuthorize: the permission says "may read rates", the scope check
    //   says "but not this person's".
    //
    // @PathVariable binds the {id} piece of the URL to the argument, and typing
    // it Long makes Spring convert it first: /api/resources/abc is answered 400
    // (MethodArgumentTypeMismatchException) and never enters this method.
    @GetMapping("/{id}")
    public ResponseEntity<ResourceResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(resourceService.findById(id));
    }

    // WHAT IT DOES / GIVES BACK
    //   POST /api/resources turns an existing account into a costed resource
    //   and answers 201 Created with the saved row plus a Location header.
    //   409 if that user is already an active resource, 404 if the userId does
    //   not exist, 400 if the body breaks a validation rule, 403 without
    //   MANAGE_RESOURCES.
    //
    // WHY IT TAKES A userId AND DOES NOT CREATE THE PERSON
    //   ADR-022 again: the account already exists, this only attaches money to
    //   it. Creating a person here would give two ways to create an employee,
    //   and the two could drift apart.
    //
    // @RequestBody: Spring reads the HTTP body and Jackson builds a
    // ResourceRequest from the JSON. Without it Spring would look for the
    // fields in the query string and pass an object full of nulls.
    //
    // @Valid runs Bean Validation on that object BEFORE the method body: the
    // rules written on ResourceRequest are checked, and a failure throws
    // MethodArgumentNotValidException, which GlobalExceptionHandler turns into
    // a 400 naming each bad field.
    // Concrete example of what it prevents: @Positive on dailyRate stops a
    // negative rate from being stored. A negative daily rate would flow into
    // the internal quote and produce a NEGATIVE cost, which reads as extra
    // margin - a wrong number that nothing downstream would flag.
    // Second example: @DecimalMax("9.9999") on tccRate matches the column
    // precision(5, scale 4). Without it, 12.5 would pass Java and be refused by
    // PostgreSQL, so the user would see a vague 409 instead of "this value is
    // too large".
    @PostMapping
    public ResponseEntity<ResourceResponse> create(@Valid @RequestBody ResourceRequest request) {
        ResourceResponse created = resourceService.create(request);
        // Absolute address of the row just created. fromCurrentRequest() starts
        // from the URL this request actually used (http://host/api/resources),
        // .path("/{id}") adds the placeholder and buildAndExpand fills it in,
        // giving http://host/api/resources/42.
        // WHY not a hand-written string: fromCurrentRequest() keeps the scheme,
        // host and port seen by the client. Behind the Docker reverse proxy a
        // hard-coded "http://localhost:8080/..." would point the browser at an
        // address that does not exist for it.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // 201 Created + Location: the HTTP way of saying "created, and here is
        // where it lives now". The body is returned too, so the screen can
        // insert the row without a second call - it needs the generated id and
        // the annualCost that only the server can compute.
        return ResponseEntity.created(location).body(created);
    }

    // WHAT IT DOES / GIVES BACK
    //   PUT /api/resources/{id} -> 200 with the updated resource. 404 if the id
    //   is unknown or soft-deleted, 400 on a validation failure, 403 without
    //   MANAGE_RESOURCES.
    //
    // NOTE WHAT IS NOT CHANGED HERE
    //   The service only copies the four rate and date fields. request.userId()
    //   is ignored on update, so a resource can never be moved from one person
    //   to another - that would silently rewrite the cost history of two
    //   people at once. To change the person, the resource is deleted and
    //   another one is created.
    //
    // WHY PUT: the body describes the complete state the row must end up in,
    // so sending it twice leaves exactly the same result. That is what makes
    // the screen's "save" button safe to press twice.
    @PutMapping("/{id}")
    public ResponseEntity<ResourceResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody ResourceRequest request) {
        return ResponseEntity.ok(resourceService.update(id, request));
    }

    // WHAT IT DOES / GIVES BACK
    //   DELETE /api/resources/{id} -> 204 No Content. 404 if unknown, 403
    //   without MANAGE_RESOURCES.
    //
    // THIS IS A SOFT DELETE, AND THAT MATTERS
    //   The service sets deleted = true; the row stays in the table. The reads
    //   all filter on deleted = false, so the resource leaves the screen, but
    //   the past stays explainable: a KPI or a quote computed last year used
    //   that rate, and a real DELETE would make those figures impossible to
    //   justify - or break the foreign keys that point at the row.
    //
    // ResponseEntity<Void> + noContent(): status 204, empty body. Nothing is
    // left to describe, and the Void type makes it impossible to add a body
    // here by accident later.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        resourceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── TCC per year (F-AFF-13 §6.3 rule 4) ───────────────────────
    //
    // WHY THESE TWO EXTRA ENDPOINTS EXIST
    //   A Resource carries ONE current daily rate and ONE current TCC rate.
    //   But rates change every year, and a project that ran in 2024 must keep
    //   being costed with the 2024 rate, otherwise a quote signed last year
    //   would not match its own figures once salaries are revised. So the
    //   history lives in its own table (tcc_annuel, one row per resource and
    //   per year) with its own sub-resource under the resource it belongs to.
    //   Putting the years inside ResourceRequest instead would mean rewriting
    //   the whole history every time somebody edits a staffing date.

    // WHAT IT DOES / GIVES BACK
    //   GET /api/resources/{id}/tcc -> 200 with the live year rows of that
    //   resource: { annee, dailyRate, tccRate }. 404 if the resource is
    //   unknown, 403 if the caller holds only VIEW_RESOURCES and this resource
    //   is outside the projects they manage (the same assertVisible() check as
    //   GET /api/resources/{id} - the permission alone is not enough).
    //
    // WHY THE PATH IS NESTED UNDER THE RESOURCE
    //   A year row has no meaning on its own: "2024, 480, 0.24" answers
    //   nothing without knowing whose rate it is. The URL carries that link, so
    //   there is no way to ask for a year row without naming its owner - and
    //   therefore no way to slip past the visibility check.
    @GetMapping("/{id}/tcc")
    public ResponseEntity<List<TccAnnuelDto>> tccAnnuels(@PathVariable Long id) {
        return ResponseEntity.ok(resourceService.findTccAnnuels(id));
    }

    // WHAT IT DOES / GIVES BACK
    //   PUT /api/resources/{id}/tcc replaces the whole year history of one
    //   resource with the list sent, and answers 200 with the saved rows.
    //   409 if the same year appears twice in one body (the service refuses it
    //   before touching the database; IllegalArgumentException is mapped to
    //   Conflict for the whole project, which the test
    //   TccAnnuelServiceTest.duplicateYearInOnePayloadIsRejected pins down).
    //   404 if the resource is unknown, 403 without MANAGE_RESOURCES.
    //
    // WHY PUT ON THE COLLECTION, AND WHAT IT REALLY DOES
    //   The screen edits a small grid of years and saves it whole, so the body
    //   describes the complete state: a year present is created or updated, a
    //   year absent from the body is soft-deleted. Sending the same body twice
    //   changes nothing the second time, which is what lets the user press save
    //   again after a network error.
    //   Careful: the service updates each existing row in place instead of
    //   deleting then reinserting. That is not a style choice - Hibernate
    //   flushes INSERTs before UPDATEs, so the reinserted row for a year
    //   arrived before the old one was marked deleted, and the partial unique
    //   index uk_tcc_annuel_resource_annee (resource_id, annee) WHERE
    //   deleted = FALSE rejected it. Editing an already-entered year answered
    //   409 every time.
    //
    // @RequestBody with a List: Jackson builds one TccAnnuelDto per element of
    // the JSON array. The body is an array, not an object - the screen sends
    // [{"annee":2024,...},{"annee":2025,...}].
    //
    // List<@Valid TccAnnuelDto>: the @Valid is written INSIDE the generic type
    // on purpose. It means "cascade the checks into each element of the list",
    // not "check the list object itself" - a list has no @NotNull or @Min to
    // check. The constraints it points at are the ones on TccAnnuelDto:
    // @Min(2000)/@Max(2100) on the year, @Positive on the daily rate,
    // @DecimalMax("9.9999") on the TCC rate.
    // Note that this is a belt-and-braces layer, not the only guard: the year
    // column is NOT NULL with its own unique index, and the duplicate-year rule
    // is re-checked in the service, which is what the test asserts.
    @PutMapping("/{id}/tcc")
    public ResponseEntity<List<TccAnnuelDto>> replaceTccAnnuels(@PathVariable Long id,
                                                                @RequestBody List<@Valid TccAnnuelDto> rates) {
        return ResponseEntity.ok(resourceService.replaceTccAnnuels(id, rates));
    }
}

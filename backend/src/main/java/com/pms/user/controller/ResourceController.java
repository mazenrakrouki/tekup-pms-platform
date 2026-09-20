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

// HTTP door to the cost referential (/api/resources): a Resource is the COST side of a person
// (ADR-022 splits account from cost — User is who can sign in, Resource is daily rate, TCC rate,
// staffing dates). Everything downstream (internal quotes, margins, EVM) reads these rates, so
// an empty referential here silently zeroes them out elsewhere.

/**
 * Human-resource referential: rates, staffing window, and the per-year TCC history (F-AFF-13
 * §6.3 rule 4). No business rule here — who may see which rows and how a year is replaced live
 * in {@link ResourceService}, so ADR-021's scope logic (rewritten here by hand since
 * ProjectScopeInterceptor only matches /api/projects/{id}/**, not a resource id) still applies
 * to any other caller, like KpiService or DevisInterneService, that bypasses the URL entirely.
 */
@Tag(name = "Ressources", description = "CRUD ressources humaines, tarifs journaliers et historique TCC annuel")
@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    /**
     * Lists the resources the caller is allowed to see: the whole referential for MANAGE_RESOURCES,
     * only the people on projects they manage for VIEW_RESOURCES alone (checked in the service,
     * not here). Not paginated — one row per staffed employee is a short, browser-filterable list.
     */
    @GetMapping
    public ResponseEntity<List<ResourceResponse>> list() {
        return ResponseEntity.ok(resourceService.findAll());
    }

    // 403 here can come from assertVisible() in the service, not just @PreAuthorize: holding
    // VIEW_RESOURCES doesn't mean this particular resource is visible to the caller.
    @GetMapping("/{id}")
    public ResponseEntity<ResourceResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(resourceService.findById(id));
    }

    /**
     * Attaches cost data to an existing account -> 201. Takes a userId rather than creating the
     * person (ADR-022: the account already exists elsewhere), so there's only one way to create
     * an employee.
     */
    @PostMapping
    public ResponseEntity<ResourceResponse> create(@Valid @RequestBody ResourceRequest request) {
        ResourceResponse created = resourceService.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    // userId is ignored on update: a resource can never be reassigned to another person, since
    // that would rewrite two people's cost history at once. Delete and recreate instead.
    @PutMapping("/{id}")
    public ResponseEntity<ResourceResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody ResourceRequest request) {
        return ResponseEntity.ok(resourceService.update(id, request));
    }

    // Soft delete: the row stays so past KPIs and quotes computed with this rate stay explainable.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        resourceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // TCC per year (F-AFF-13 §6.3 rule 4): a Resource holds only the CURRENT rate, but a project
    // must keep being costed at the rate of the year it ran in, so history lives in its own
    // per-year sub-resource rather than being rewritten every time a rate changes.

    // Same assertVisible() scope check as GET /{id}: nested under the resource so a year row can
    // never be requested without naming (and checking visibility of) its owner.
    @GetMapping("/{id}/tcc")
    public ResponseEntity<List<TccAnnuelDto>> tccAnnuels(@PathVariable Long id) {
        return ResponseEntity.ok(resourceService.findTccAnnuels(id));
    }

    /**
     * Replaces the whole year history with the list sent: a year present is created or updated,
     * one absent is soft-deleted. The service updates existing rows in place rather than
     * delete-then-reinsert, because Hibernate flushes INSERTs before UPDATEs and the reinsert
     * would collide with the still-live old row on the partial unique index.
     */
    @PutMapping("/{id}/tcc")
    public ResponseEntity<List<TccAnnuelDto>> replaceTccAnnuels(@PathVariable Long id,
                                                                @RequestBody List<@Valid TccAnnuelDto> rates) {
        return ResponseEntity.ok(resourceService.replaceTccAnnuels(id, rates));
    }
}

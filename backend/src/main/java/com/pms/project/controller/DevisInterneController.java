package com.pms.project.controller;

import com.pms.project.dto.DevisInterneResponse;
import com.pms.project.dto.LigneDiRequest;
import com.pms.project.service.DevisInterneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST entry point for a project's Devis Interne (DI: what was sold to the client vs. what the
 * work really costs, line by line, with the resulting margin). Four calls: read the quote, add
 * a line, change a line, remove a line. Nothing here does arithmetic or permission checks —
 * both are enforced by DevisInterneService — and every amount is recomputed on read, never
 * stored, since a stored total could go stale the moment the exchange rate or another line
 * changes (F-AFF-13).
 */
// Groups these four endpoints under one heading on the generated Swagger page; changes nothing
// at runtime. French text matches every other @Tag in the backend. The role named in the
// description is documentation only — the code itself tests the MANAGE_DI capability, never a
// role name, so granting it to another role is a data change, not a code change.
@Tag(name = "Devis Interne", description = "Structure DI vide + calcul marges (accès MANAGE_DI — Directeur uniquement)")
@RestController
// projectId in the path (not the body) is what ProjectScopeInterceptor matches to enforce the
// ADR-021 perimeter check before any method here runs.
@RequestMapping("/api/projects/{projectId}/devis-interne")
@RequiredArgsConstructor
public class DevisInterneController {

    // The MANAGE_DI check, the two-pass calculation engine, and the line/project pairing check
    // all live on the other side of this field; this class only turns HTTP into a method call.
    private final DevisInterneService devisInterneService;

    /**
     * GET: the whole internal quote (currency, rate, every line with computed amounts, totals).
     * 200 with an empty list/zero totals for a project with no lines yet — not 404 — since the
     * DI is simply the set of lines attached to the project, not a separate object to create first.
     */
    @GetMapping
    public ResponseEntity<DevisInterneResponse> get(@PathVariable Long projectId) {
        return ResponseEntity.ok(devisInterneService.getDevisInterne(projectId));
    }

    /**
     * POST: adds one line, returns 200 with the WHOLE recomputed quote. Percentage-rate lines
     * (AUTRES_FRAIS: taxes, fees, risk provision) are costed against the sold total in TND, so
     * one new line changes every percentage line's cost and the overall margin — returning only
     * the created line would leave the screen showing a stale, wrong margin. 200 rather than 201
     * because the body isn't the created resource, and there's no GET for a single DI line.
     */
    // @Valid enforces LigneDiRequest's rules (required section, max lengths, non-negative
    // amounts, tauxPourcentage in [0,1]) before arithmetic runs on them — a bad field here would
    // quietly move the margin of the whole quote (a rate typed as 5 instead of 0.05 would price
    // a 5% provision five times over). @RequestBody (a DTO, not the LigneDi entity) prevents a
    // caller from posting {"project": {"id": 9}} and attaching the line to another project.
    @PostMapping("/lignes")
    public ResponseEntity<DevisInterneResponse> addLigne(@PathVariable Long projectId,
                                                         @Valid @RequestBody LigneDiRequest request) {
        return ResponseEntity.ok(devisInterneService.addLigne(projectId, request));
    }

    /**
     * PUT: replaces one line's whole content, 200 with the recomputed quote (same reason as
     * addLigne). Full-body PUT means the server never has to guess "leave alone" vs. "clear" —
     * an expense field left blank by accident would otherwise quietly inflate the line's cost.
     */
    // Both ids are used: the service refuses a line whose project isn't projectId, which
    // ADR-021's interceptor alone can't catch (it only sees the URL's project number).
    @PutMapping("/lignes/{ligneId}")
    public ResponseEntity<DevisInterneResponse> updateLigne(@PathVariable Long projectId,
                                                            @PathVariable Long ligneId,
                                                            @Valid @RequestBody LigneDiRequest request) {
        return ResponseEntity.ok(devisInterneService.updateLigne(projectId, ligneId, request));
    }

    /**
     * DELETE: soft-deletes one line (flag set, row kept for audit), 200 with the recomputed
     * quote rather than 204 — removing a line changes the sold total and therefore every
     * percentage-rate line's cost and the net margin, so the screen needs fresh totals, not a
     * stale display next to the now-missing row.
     */
    // projectId lets the service refuse a line belonging to another project, as in updateLigne.
    @DeleteMapping("/lignes/{ligneId}")
    public ResponseEntity<DevisInterneResponse> deleteLigne(@PathVariable Long projectId,
                                                            @PathVariable Long ligneId) {
        return ResponseEntity.ok(devisInterneService.deleteLigne(projectId, ligneId));
    }
}

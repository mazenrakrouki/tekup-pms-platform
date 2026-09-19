package com.pms.governance.entity;

// =============================================================================
// FILE: StatutRisque.java   ("Risk status")
// =============================================================================
// WHAT THIS FILE IS
//   A Java enum: a small closed list of allowed values. These three words are the
//   only states a line of the risk register can be in.
//
// WHERE IT SITS IN THE FLOW
//   It is the type of Risk.statut, in this same package.
//   Unlike the deliverable and the change request, this one IS part of RiskRequest
//   (marked @NotNull there), so the project manager picks it in the same form that
//   edits the rest of the risk, and RiskService.create() and update() copy it
//   straight onto the entity. Those methods are guarded by
//   @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')"), and the URL
//   /api/projects/{projectId}/risks is additionally checked against the caller's
//   project scope (ADR-021), so "who may write" is still fully controlled.
//   It goes back to the browser through RiskResponse. In the database it is stored
//   as text, because the entity field is marked @Enumerated(EnumType.STRING).
//
// WHY IT EXISTS
//   Without it the status would be a plain String and nothing would stop "ouvert",
//   "closed" or an empty value from being saved. The governance screen could no
//   longer show how many risks are still open, which is the first number anybody
//   looks at in a project review.
//   Jackson, the library that reads the JSON body, also uses this list: a request
//   asking for "statut": "ARCHIVE" is rejected with a clean 400 error before any
//   code runs.
//
// WHY THERE IS NO STATE MACHINE HERE
//   A risk legitimately moves in both directions: one that was closed can be
//   reopened when the problem comes back. Forcing a one-way path, as the
//   deliverable and the change request do, would push people to create a duplicate
//   risk line instead of reopening the real one.
//   Careful not to confuse this with deletion: RiskService.delete() does not use
//   this field at all, it sets the "deleted" flag inherited from BaseEntity, and
//   the repository queries filter on "deleted = false". FERME means "the risk is
//   over"; deleted means "this line should never have been written".
//
// WARNING FOR ANYONE EDITING THIS FILE
//   These three names are written as text in risks.statut and they are repeated in
//   the CHECK constraint chk_risk_statut of V11__schema_governance.sql. Renaming
//   one here without a matching migration would make every old row unreadable:
//   Hibernate would throw "No enum constant" as soon as somebody opens the risk
//   list. The column is VARCHAR(10), so a longer name would need a schema change.
//
// THE VALUES
//   OUVERT : open      - the default for a new risk, it may still happen
//   MITIGE : mitigated - a plan is in place and the risk is under control
//   FERME  : closed    - it happened and was handled, or it can no longer happen
// =============================================================================
public enum StatutRisque {
    OUVERT, MITIGE, FERME
}

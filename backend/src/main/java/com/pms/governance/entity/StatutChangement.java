package com.pms.governance.entity;

// =============================================================================
// FILE: StatutChangement.java   ("Change request status")
// =============================================================================
// WHAT THIS FILE IS
//   A Java enum: a small closed list of allowed values. These three words are the
//   only states a change request can be in.
//
// WHERE IT SITS IN THE FLOW
//   It is the type of DemandeChangement.statut, in this same package.
//   It is NOT part of DemandeChangementRequest, so a client cannot choose it in a
//   JSON body. It only moves inside DemandeChangementService, which sets it in
//   approuver() and rejeter(), both guarded by
//   @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')").
//   It goes out to the browser through DemandeChangementResponse. In the database
//   it is stored as text, because the entity field is marked
//   @Enumerated(EnumType.STRING).
//
// WHY IT EXISTS
//   This enum is the state machine of the whole change-request feature. Every
//   write method of DemandeChangementService starts by comparing the current value
//   with EN_ATTENTE, and refuses the operation with a BusinessRuleException when
//   it is anything else:
//     - update() : a request that was already decided can no longer be rewritten
//     - delete() : a decided request can no longer be removed from the log
//     - approuver() / rejeter() : a request can only be decided once
//   Example of what would break without those checks: a request approved on Monday
//   could be quietly edited on Tuesday, and the approval on file would no longer
//   describe what was actually approved. On a governance log that is the one thing
//   that must never happen.
//
// THE ONLY ALLOWED MOVES
//   EN_ATTENTE -> APPROUVE   (approuver(), also stamps dateDecision with today)
//   EN_ATTENTE -> REJETE     (rejeter(),   also stamps dateDecision with today)
//   Nothing ever goes back to EN_ATTENTE, and APPROUVE and REJETE never turn into
//   each other. A wrong decision is corrected by opening a new request, which
//   keeps both the mistake and the correction visible.
//
// WARNING FOR ANYONE EDITING THIS FILE
//   These three names are written as text in demandes_changement.statut and they
//   are repeated in the CHECK constraint chk_dc_statut of
//   V11__schema_governance.sql. Renaming one here without a matching migration
//   would make every old row unreadable: Hibernate would throw "No enum constant"
//   as soon as somebody opens the list.
//
// THE VALUES
//   EN_ATTENTE : waiting  - the default for a new request; dateDecision is null
//   APPROUVE   : approved - the change was accepted
//   REJETE     : rejected - the change was refused
// =============================================================================
public enum StatutChangement {
    EN_ATTENTE, APPROUVE, REJETE
}

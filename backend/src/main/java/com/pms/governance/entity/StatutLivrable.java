package com.pms.governance.entity;

// =============================================================================
// FILE: StatutLivrable.java   ("Deliverable status")
// =============================================================================
// WHAT THIS FILE IS
//   A Java enum: a small closed list of allowed values. These four words are the
//   only states a deliverable can be in.
//
// WHERE IT SITS IN THE FLOW
//   It is the type of Livrable.statut, in this same package.
//   It is NOT part of LivrableRequest, so a client cannot set it directly in a
//   JSON body. It only moves through the dedicated methods of LivrableService -
//   demarrer(), livrer() and valider() - each one guarded by
//   @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')").
//   It goes back to the browser through LivrableResponse. In the database it is
//   stored as text, because the entity field is marked
//   @Enumerated(EnumType.STRING).
//
// WHY IT EXISTS
//   This enum is the state machine of the deliverable feature. LivrableService
//   reads the current value before every write and refuses the operation with a
//   BusinessRuleException when the move makes no sense:
//     - demarrer() : only from EN_ATTENTE
//     - livrer()   : from any state except VALIDE
//     - valider()  : only from LIVRE
//     - update()   : refused once the deliverable is VALIDE
//     - delete()   : refused once the deliverable is VALIDE
//   Example of what would break without those checks: a deliverable already
//   accepted by the client could be renamed or deleted afterwards, and the project
//   record would no longer match what was actually handed over.
//   Note that VALIDE is a final state on purpose: nothing in the service can move
//   a deliverable out of it.
//
// THE NORMAL PATH
//   EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE
//   livrer() also accepts a jump straight from EN_ATTENTE to LIVRE, for a small
//   deliverable that is handed over without ever being tracked as "in progress".
//
// WARNING FOR ANYONE EDITING THIS FILE
//   These four names are written as text in livrables.statut and they are repeated
//   in the CHECK constraint chk_livrable_statut of V11__schema_governance.sql.
//   Renaming one here without a matching migration would make every old row
//   unreadable: Hibernate would throw "No enum constant" as soon as somebody opens
//   the deliverable list. The column is VARCHAR(15), so a longer name would need a
//   schema change as well.
//
// THE VALUES
//   EN_ATTENTE : waiting     - the default for a new deliverable, nothing started
//   EN_COURS   : in progress - work has begun
//   LIVRE      : delivered   - handed over, waiting for the client to accept it
//   VALIDE     : accepted    - the client agreed; the row is frozen from now on
// =============================================================================
public enum StatutLivrable {
    EN_ATTENTE, EN_COURS, LIVRE, VALIDE
}

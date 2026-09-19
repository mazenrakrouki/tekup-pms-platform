package com.pms.governance.entity;

// =============================================================================
// FILE: PrioriteChangement.java   ("Change request priority")
// =============================================================================
// WHAT THIS FILE IS
//   A Java enum: a small closed list of allowed values. Only these four words can
//   be used as the priority of a change request, and the compiler refuses anything
//   else.
//
// WHERE IT SITS IN THE FLOW
//   It is the type of DemandeChangement.priorite, in this same package.
//   It comes in from the browser through DemandeChangementRequest (marked
//   @NotNull there, so the caller must choose one), and it goes back out through
//   DemandeChangementResponse. In the database it is stored as text, because the
//   entity field is marked @Enumerated(EnumType.STRING).
//
// WHY IT EXISTS
//   Without it the priority would be a plain String and nothing would stop
//   "urgent", "P1" or an empty value from being saved. Sorting or counting the
//   waiting requests by priority would then be impossible, and the screen could
//   not colour the badge.
//   Jackson, the library that reads the JSON body, also uses this list: a request
//   asking for "priorite": "SUPER_URGENT" is rejected with a clean 400 error
//   before any code runs.
//
// WHY IT IS SEPARATE FROM NiveauRisque
//   NiveauRisque has three steps for a risk scale; a change request needs four,
//   with NORMALE as the everyday case and CRITIQUE kept for real emergencies.
//   Keeping them apart means a change to one scale cannot disturb the other.
//
// WARNING FOR ANYONE EDITING THIS FILE
//   These four names are written as text in demandes_changement.priorite and they
//   are repeated in the CHECK constraint chk_dc_priorite of
//   V11__schema_governance.sql. Renaming one here without a matching migration
//   would make every old row unreadable: Hibernate would throw "No enum constant"
//   as soon as somebody opens the change-request list. The column is also only
//   VARCHAR(10), so a longer name would need a schema change too.
//
// THE VALUES, LOWEST FIRST
//   FAIBLE   : low      - nice to have, can wait
//   NORMALE  : normal   - the default set by DemandeChangement when nothing is chosen
//   ELEVEE   : high     - should be decided soon
//   CRITIQUE : critical - blocks the project until it is decided
//   The order written here is not used for sorting: the value is stored as text,
//   so an SQL ORDER BY would sort it alphabetically, and
//   DemandeChangementRepository sorts the list by dateDemande instead.
// =============================================================================
public enum PrioriteChangement {
    FAIBLE, NORMALE, ELEVEE, CRITIQUE
}

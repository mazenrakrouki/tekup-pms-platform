package com.pms.governance.entity;

// State machine for Livrable.statut: EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE (livrer() also
// allows EN_ATTENTE -> LIVRE directly), driven only by demarrer()/livrer()/valider() in
// LivrableService, never from the request body. VALIDE is final: update()/delete() are refused
// once reached, so an accepted deliverable can't be silently changed. Stored as text; renaming
// a value needs a matching migration for chk_livrable_statut in V11.
public enum StatutLivrable {
    EN_ATTENTE, EN_COURS, LIVRE, VALIDE
}

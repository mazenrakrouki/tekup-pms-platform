package com.pms.governance.entity;

// State machine for DemandeChangement.statut: EN_ATTENTE -> APPROUVE or REJETE only, via
// approuver()/rejeter() in DemandeChangementService (never from the request body), which also
// refuse update()/delete() once decided so an approval on file can never be silently rewritten.
// Stored as text; renaming a value needs a matching migration for chk_dc_statut in V11.
public enum StatutChangement {
    EN_ATTENTE, APPROUVE, REJETE
}

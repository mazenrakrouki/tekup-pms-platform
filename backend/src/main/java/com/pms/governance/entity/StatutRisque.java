package com.pms.governance.entity;

// Risk.statut: OUVERT/MITIGE/FERME. Unlike Livrable/DemandeChangement, this one IS part of
// RiskRequest and has no forced state machine, because a closed risk can legitimately reopen if
// the problem comes back. Distinct from soft-deletion (BaseEntity.deleted): FERME means "the risk
// is over", deleted means "this line should never have existed". Stored as text; renaming a value
// needs a matching migration for chk_risk_statut in V11.
public enum StatutRisque {
    OUVERT, MITIGE, FERME
}

package com.pms.governance.entity;

// Four-step priority scale for DemandeChangement.priorite (FAIBLE < NORMALE < ELEVEE < CRITIQUE),
// separate from the three-step NiveauRisque so the two scales can evolve independently. Stored as
// text (@Enumerated(EnumType.STRING)) so renaming a value here needs a matching migration for
// demandes_changement.priorite (chk_dc_priorite CHECK constraint, VARCHAR(10)).
public enum PrioriteChangement {
    FAIBLE, NORMALE, ELEVEE, CRITIQUE
}

package com.pms.governance.entity;

// Three-step scale (low/medium/high) shared by Risk.probabilite/impact and
// PartiePrenante.influence/interet, stored as text (@Enumerated(EnumType.STRING)) so renaming a
// value here needs a matching migration for risks.probabilite/impact and
// parties_prenantes.influence/interet (chk_risk_* / chk_pp_* CHECK constraints, VARCHAR(10)).
public enum NiveauRisque {
    FAIBLE, MOYEN, ELEVE
}

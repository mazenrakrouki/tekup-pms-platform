package com.pms.project.entity;

/**
 * The three blocks of the Devis Interne (DI, the internal quote), per the company's Excel model
 * (F-AFF-13 §3.1). Not just a label: in DevisInterneService.compute(), "section == AUTRES_FRAIS
 * and tauxPourcentage is not null" sends a line down a percentage-of-total formula instead of
 * days x daily cost, which is why the engine computes totals in two passes. Names are mirrored
 * by the DB constraint chk_ligne_di_section (V23) and must not change without a migration.
 */
public enum SectionDi {
    // Fees: the heart of the quote. One line per contractual profile sold (for example
    // "PC-1 Chef de mission") and per internal person really staffed on it. These lines
    // carry days sold, a unit selling price, internal days and a daily internal cost (TCC).
    HONORAIRES,
    // Expenses tied to running the mission: per diems (the daily allowance paid to someone
    // working away from home) and travel. Costed from the plain amount fields, like fees.
    FRAIS,
    // Everything that is charged as a share of what was sold: local taxes, registration fees
    // and the risk provision. These are the lines that use tauxPourcentage (for example
    // 0.05 for 5 %), and they are the reason the calculation needs two passes.
    AUTRES_FRAIS
}

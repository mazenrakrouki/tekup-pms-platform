package com.pms.project.entity;

/**
 * WHAT THIS FILE IS
 * The three blocks of the Devis Interne (DI = the internal quote, the sheet where the company
 * works out what a project really costs it against what it sold). Every line of the quote
 * belongs to exactly one of these three blocks. The blocks are those of the company's own
 * Excel model, traceability reference F-AFF-13 section 3.1.
 *
 * WHERE IT SITS IN THE FLOW
 * The browser sends the block name as text in the JSON body of POST or PUT
 * /api/projects/{projectId}/devis-interne/lignes;
 *   -> Jackson (the library that turns JSON text into Java objects) matches it to one of the
 *      three names below and fills LigneDiRequest.section, which is marked as required;
 *   -> DevisInterneService.apply() copies it onto LigneDi.section;
 *   -> it is stored as text in the lignes_di.section column;
 *   -> DevisInterneService.compute() reads it back to decide how the cost of that line is
 *      worked out, and LigneDiResponse carries it to the DI screen, which groups the lines
 *      under three headings.
 * This file calls nothing itself.
 *
 * WHY IT EXISTS: THIS ONE REALLY CHANGES THE ARITHMETIC
 * Unlike most enums, this is not only a label. In DevisInterneService.compute() the test
 * "section == AUTRES_FRAIS and tauxPourcentage is not null" sends the line down a completely
 * different formula: its cost is a percentage of the whole sold total in TND instead of
 * internal days multiplied by a daily cost. That single test is the reason the engine works
 * in two passes - it must know the sold total before it can price those lines. Remove this
 * enum and a 5 % risk provision would be costed as zero internal days, that is zero cost, and
 * the quote would show a margin that is too high.
 *
 * WHY THE NAMES MUST NOT CHANGE
 * Flyway V23 created lignes_di with the constraint chk_ligne_di_section, which accepts only
 * ('HONORAIRES', 'FRAIS', 'AUTRES_FRAIS'). Adding or renaming a constant here without a new
 * migration compiles, then fails on the first save with: new row violates check constraint
 * "chk_ligne_di_section".
 *
 * ABOUT THE ORDER OF THE THREE NAMES
 * They are written in the order of the Excel sheet, which is how the DI screen shows them,
 * but that screen keeps its own fixed list of the three sections and groups the lines itself.
 * The query LigneDiRepository.findActiveByProjectId orders by the section column, and since
 * the value is stored as text that ordering is alphabetical, not the order below. So treat
 * the order here as a description of the Excel layout, not as something the screen depends on.
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

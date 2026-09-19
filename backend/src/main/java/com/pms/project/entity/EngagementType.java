package com.pms.project.entity;

/**
 * WHAT THIS FILE IS
 * The closed list of the two kinds of contract the company signs, as written on the Excel
 * sheet "Fiche d'identification" (the project identity card): FORFAIT, a fixed price agreed
 * up front for an agreed scope of work, or REGIE, where the client pays for the days that
 * were really worked.
 *
 * WHERE IT SITS IN THE FLOW
 * The browser sends the value as plain text inside the JSON body of POST or PUT
 * /api/projects;
 *   -> Jackson (the library that turns JSON text into Java objects) matches that text to one
 *      of the two names below and fills ProjectRequest.engagementType;
 *   -> ProjectService.applyFicheIdentification() calls project.setEngagementType(...);
 *   -> Project.engagementType is written to the projects.engagement_type column as text,
 *      because the field carries the EnumType.STRING setting;
 *   -> on the way back, ProjectMapper copies it into ProjectResponse for the project sheet
 *      screen.
 * This file calls nothing itself. It only names the allowed values.
 *
 * WHY IT EXISTS
 * Delete it and the field becomes a free String, so "FORFAIT", "forfait" and "Régie" would
 * all be stored. A director filtering the portfolio on fixed-price contracts would then miss
 * half of them. Being an enum, Jackson refuses any other text with a 400 error before
 * anything is saved. Flyway V14 created engagement_type as a plain VARCHAR(20) with no CHECK
 * constraint, so this Java file is the only guard on that column.
 *
 * WHY THE NAMES STAY IN FRENCH
 * They are the words used on the company's own Excel sheet, and the same text is what sits
 * in the database column. Renaming a constant would break every row already saved: Hibernate
 * would read "REGIE" from the column, find no matching constant, and fail with an
 * IllegalArgumentException.
 *
 * WHAT USES THE VALUE
 * Today no calculation in the backend branches on this field. Note in particular that the
 * internal quote (Devis Interne) always multiplies sold days by a unit selling price, and the
 * invoicing milestones always work on the project budget, whichever value is chosen here. So
 * the field is descriptive information on the project sheet, kept for reporting.
 */
public enum EngagementType {
    // Fixed price: one agreed amount for an agreed scope. The company carries the risk if
    // the work takes more days than planned.
    FORFAIT,
    // "Regie" = time and materials: the client pays for the days actually worked, so the
    // client carries the risk of extra days.
    REGIE
}

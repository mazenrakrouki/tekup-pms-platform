package com.pms.project.entity;

/**
 * WHAT THIS FILE IS
 * The closed list of the two ways the company can be engaged on a project, exactly as the
 * Excel sheet "Fiche d'identification" (the project identity card) writes them: the company
 * works alone, or the company works inside a group of companies.
 *
 * WHERE IT SITS IN THE FLOW
 * The browser sends the value as plain text inside the JSON body of POST or PUT
 * /api/projects;
 *   -> Jackson (the library that turns JSON text into Java objects) matches that text to one
 *      of the two names below and fills ProjectRequest.businessModel;
 *   -> ProjectService.applyFicheIdentification() calls project.setBusinessModel(...);
 *   -> Project.businessModel is written to the projects.business_model column as text,
 *      because the field carries the EnumType.STRING setting;
 *   -> on the way back, ProjectMapper copies it into ProjectResponse for the project sheet
 *      screen.
 * This file calls nothing itself. It only names the allowed values.
 *
 * WHY IT EXISTS
 * Delete it and the field becomes a free String. Then "SEUL", "seul", "Seul" and a typo such
 * as "SEULE" would all be saved, and a report grouping projects by business model would show
 * four groups instead of two. Because it is an enum, Jackson refuses any other text with a
 * 400 error at the door of the application, so the bad value never reaches the database.
 * This matters more than usual here: Flyway V14 created business_model as a plain
 * VARCHAR(20) with no CHECK constraint, so this Java file is the only guard on that column.
 *
 * WHY THE NAMES STAY IN FRENCH
 * These are the exact words of the Excel sheet the company already uses, and the very same
 * text is what sits in the database column. Renaming a constant here would make every row
 * already saved unreadable: Hibernate would find "GROUPEMENT" in the column, find no
 * matching constant, and fail the read with an IllegalArgumentException.
 *
 * WHAT USES THE VALUE
 * Today no calculation in the backend branches on this field. It is descriptive information
 * shown on the project sheet and kept for reporting, which is why no service reads it back
 * apart from the mapper.
 */
public enum BusinessModel {
    // The company answers the call for tenders on its own and signs the contract alone.
    SEUL,
    // "Groupement" = consortium: several companies answer together and share one contract.
    GROUPEMENT
}

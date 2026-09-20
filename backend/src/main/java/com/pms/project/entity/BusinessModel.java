package com.pms.project.entity;

// The two ways the company is engaged on a project, per the "Fiche d'identification" Excel
// sheet: alone, or inside a group of companies. Kept as an enum (not a free String) because
// Flyway V14 created business_model as a plain VARCHAR(20) with no CHECK constraint — this file
// is the only guard on that column. Names stay in French to match the Excel sheet and the values
// already stored in the database; renaming a constant would break every saved row.
public enum BusinessModel {
    // The company answers the call for tenders on its own and signs the contract alone.
    SEUL,
    // "Groupement" = consortium: several companies answer together and share one contract.
    GROUPEMENT
}

package com.pms.project.entity;

// The two kinds of contract the company signs, per the "Fiche d'identification" Excel sheet:
// FORFAIT (fixed price) or REGIE (time and materials). Kept as an enum because Flyway V14
// created engagement_type as a plain VARCHAR(20) with no CHECK constraint — this file is the
// only guard on that column. Names stay in French to match the Excel sheet and the values
// already stored; renaming a constant would break every saved row. No calculation in the
// backend currently branches on this value; it is descriptive, kept for reporting.
public enum EngagementType {
    // Fixed price: one agreed amount for an agreed scope. The company carries the risk if
    // the work takes more days than planned.
    FORFAIT,
    // "Regie" = time and materials: the client pays for the days actually worked, so the
    // client carries the risk of extra days.
    REGIE
}

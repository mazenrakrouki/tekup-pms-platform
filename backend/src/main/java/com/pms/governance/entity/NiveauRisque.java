package com.pms.governance.entity;

// =============================================================================
// FILE: NiveauRisque.java   ("Risk level" - a three-step scale)
// =============================================================================
// WHAT THIS FILE IS
//   A Java enum: a small closed list of allowed values. Only these three words
//   can ever be used, and the compiler refuses anything else.
//
// WHERE IT SITS IN THE FLOW
//   It is used as a field type in two entities of this same package:
//     - Risk.probabilite and Risk.impact  (how likely / how bad)
//     - PartiePrenante.influence and PartiePrenante.interet
//       (how much weight / how much interest a stakeholder has)
//   It travels all the way out: RiskRequest and PartiePrenanteRequest accept it
//   from the browser, RiskResponse and PartiePrenanteResponse send it back, and
//   the Angular side turns each name into a translation key ("riskLevel.ELEVE").
//   In the database it is stored as text, because both entities mark the field
//   with @Enumerated(EnumType.STRING).
//
// WHY IT EXISTS
//   Without it these fields would be plain Strings, and nothing would stop a
//   caller from saving "eleve", "HIGH" or "tres eleve". The screen could then no
//   longer colour the badges, and no report could count risks by level.
//   It is also the single place that defines the scale, so the four fields listed
//   above are guaranteed to speak the same language.
//
// WHY THE SAME ENUM IS REUSED FOR STAKEHOLDERS
//   Influence and interest use exactly the same three steps as a risk level, and
//   the front end already has the three labels translated. Reusing it keeps one
//   vocabulary in the interface instead of two lists that say the same thing.
//
// WARNING FOR ANYONE EDITING THIS FILE
//   The three names below are written as text in four database columns
//   (risks.probabilite, risks.impact, parties_prenantes.influence,
//   parties_prenantes.interet) and they are repeated in the CHECK constraints of
//   V11__schema_governance.sql (chk_risk_probabilite, chk_risk_impact,
//   chk_pp_influence, chk_pp_interet). Renaming FAIBLE here without a matching
//   migration would make every old row unreadable: Hibernate would throw
//   "No enum constant" as soon as somebody opens the risk list.
//   Adding a new level is also a schema change: the CHECK constraints would
//   reject it, and the columns are only VARCHAR(10).
//
// THE VALUES, LOWEST FIRST
//   FAIBLE : low     - unlikely, or little damage, or a stakeholder to keep informed
//   MOYEN  : medium  - the default used by both entities when nothing is chosen
//   ELEVE  : high    - likely, or serious damage, or a stakeholder to handle first
//   The order they are written in is not used for sorting anywhere: because the
//   values are stored as text, an SQL ORDER BY on these columns would sort them
//   alphabetically (ELEVE, FAIBLE, MOYEN), which is why RiskRepository sorts the
//   risk list by creation date instead.
// =============================================================================
public enum NiveauRisque {
    FAIBLE, MOYEN, ELEVE
}

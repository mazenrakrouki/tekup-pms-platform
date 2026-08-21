package com.pms.project.entity;

/** Sections du Devis Interne (F-AFF-13 §3.1). */
public enum SectionDi {
    HONORAIRES,   // lignes par profil contractuel / ressource interne
    FRAIS,        // perdiems, voyages
    AUTRES_FRAIS  // taxes locales, enregistrement, provision risque (lignes en % du total vendu)
}

package com.pms.agile.entity;

/**
 * Cycle de vie d'un sprint.
 *
 * Valeurs en anglais : ce module est nouveau, aucune ligne n'existe en base, donc
 * l'anglais ne coûte aucune migration (cf. docs/ENGLISH_MIGRATION_AUDIT.md §5).
 * Aligné sur {@code ProjectStatus}, déjà en anglais.
 */
public enum SprintStatus {
    PLANNED,
    ACTIVE,
    CLOSED
}

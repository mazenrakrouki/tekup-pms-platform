package com.pms.project.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Cycle de vie d'un projet. Chaque statut connaît ses successeurs légaux :
 * DRAFT → ACTIVE | CANCELLED ; ACTIVE ⇄ ON_HOLD ; ACTIVE → COMPLETED | CANCELLED ;
 * COMPLETED et CANCELLED sont terminaux (l'archivage est un drapeau, pas un statut).
 */
public enum ProjectStatus {
    DRAFT,
    ACTIVE,
    ON_HOLD,
    COMPLETED,
    CANCELLED;

    private Set<ProjectStatus> successors;

    static {
        DRAFT.successors     = EnumSet.of(ACTIVE, CANCELLED);
        ACTIVE.successors    = EnumSet.of(ON_HOLD, COMPLETED, CANCELLED);
        ON_HOLD.successors   = EnumSet.of(ACTIVE, CANCELLED);
        COMPLETED.successors = EnumSet.noneOf(ProjectStatus.class);
        CANCELLED.successors = EnumSet.noneOf(ProjectStatus.class);
    }

    public boolean canTransitionTo(ProjectStatus target) {
        return this == target || successors.contains(target);
    }
}

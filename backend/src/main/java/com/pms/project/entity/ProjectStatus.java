package com.pms.project.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * A project's life cycle and its legal transitions: DRAFT -> ACTIVE or CANCELLED; ACTIVE <->
 * ON_HOLD; ACTIVE -> COMPLETED or CANCELLED; COMPLETED/CANCELLED are end states (archiving is a
 * separate flag, not a status). ProjectService.changeStatus() enforces the rule via
 * canTransitionTo() below before writing; ProjectService.archive() requires COMPLETED. Names are
 * mirrored by the DB constraint chk_status (V5) and must not change without a migration.
 */
public enum ProjectStatus {
    // Being prepared. The project sheet exists but the work has not started.
    // This is the value Project gives itself when nothing is chosen.
    DRAFT,
    // The work is running. This is the only state from which a project can be finished.
    ACTIVE,
    // Temporarily stopped (client waiting, funding frozen). Kept apart from CANCELLED
    // because the project is expected to come back: ON_HOLD can return to ACTIVE.
    ON_HOLD,
    // Finished normally. End state: nothing follows it. It is also the only status that
    // lets ProjectService.archive() move the project out of the active lists.
    COMPLETED,
    // Stopped for good, work not finished. End state.
    // Kept apart from the "deleted" flag of BaseEntity: a cancelled project is still read,
    // still reported on, and still carries its history, while a deleted one is hidden
    // everywhere.
    CANCELLED;

    // Successors of each constant, filled in the static block below. Private with no getter:
    // the set is mutable, and exposing it would let a caller rewrite the life cycle at runtime.
    private Set<ProjectStatus> successors;

    // Runs once at class load, after all five constants exist — an enum constant can't
    // reference another constant of the same enum from its own constructor ("illegal forward
    // reference"), so the sets are filled here instead.
    static {
        // From DRAFT the project can start, or be dropped before it ever starts.
        // ON_HOLD is absent on purpose: a project that never started cannot be paused.
        DRAFT.successors     = EnumSet.of(ACTIVE, CANCELLED);
        // ACTIVE is the hub: it can be paused, finished, or stopped for good.
        ACTIVE.successors    = EnumSet.of(ON_HOLD, COMPLETED, CANCELLED);
        // A paused project resumes or is stopped for good. COMPLETED is absent on purpose:
        // a project has to be running again before it can be declared finished.
        ON_HOLD.successors   = EnumSet.of(ACTIVE, CANCELLED);
        // Empty sets, not null, so canTransitionTo() can call successors.contains(...)
        // unconditionally and answer false instead of throwing NullPointerException.
        COMPLETED.successors = EnumSet.noneOf(ProjectStatus.class);
        CANCELLED.successors = EnumSet.noneOf(ProjectStatus.class);
    }

    /**
     * Whether this status may move to the given one. "this == target" comes first so
     * re-sending the current status (double click, refresh, retry) is a no-op, not an error.
     */
    public boolean canTransitionTo(ProjectStatus target) {
        return this == target || successors.contains(target);
    }
}

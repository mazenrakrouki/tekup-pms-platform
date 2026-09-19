package com.pms.project.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * WHAT THIS FILE IS
 * The life cycle of a project: the five states a project can be in, plus the rule that says
 * which state may follow which. Translated from the original French note: each status knows
 * its legal successors. DRAFT -> ACTIVE or CANCELLED; ACTIVE goes to ON_HOLD and back;
 * ACTIVE -> COMPLETED or CANCELLED; COMPLETED and CANCELLED are end states (archiving is a
 * separate flag, not a status).
 *
 * WHERE IT SITS IN THE FLOW
 * The browser calls PATCH /api/projects/{id}/status with the wanted status;
 *   -> ProjectController hands it to ProjectService.changeStatus();
 *   -> that method asks project.getStatus().canTransitionTo(newStatus) below, and throws
 *      BusinessRuleException when the answer is false;
 *   -> only then does it write the new value to Project.status, which is saved as text in
 *      the projects.status column.
 * ProjectService.archive() also reads this enum: a project can be archived only while its
 * status is COMPLETED.
 * This file calls nothing else. It holds the rule and nothing more.
 *
 * WHY IT EXISTS, AND WHY THE RULE LIVES HERE
 * Without this file the status would be free text and any jump would be possible: a
 * CANCELLED project could be set back to ACTIVE, and a project could go straight from DRAFT
 * to COMPLETED without ever having been worked on. Every figure that PMS builds on top of a
 * project (timesheets, invoicing milestones, KPI snapshots) would then sit under a state
 * that never really happened.
 * The obvious alternative was to write the allowed jumps as a chain of "if" tests inside
 * ProjectService. They are kept here instead because the status and its rule then travel
 * together: any future caller that changes a status has the check within reach, and the map
 * below can be read in one look, which is what a jury or a new developer needs.
 *
 * WHY THE NAMES MUST NOT CHANGE
 * Two places outside this file repeat these five words. Project.status stores the name as
 * text (EnumType.STRING), and Flyway V5 added the constraint chk_status, which accepts only
 * ('DRAFT','ACTIVE','ON_HOLD','COMPLETED','CANCELLED'). So adding a sixth constant here
 * without a new migration compiles fine and then fails at run time on the first save, with
 * PostgreSQL refusing the row: new row violates check constraint "chk_status".
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

    /*
     * The states that may follow this one. Each of the five constants above carries its own
     * set, so the whole life cycle is described by the five lines of the block underneath.
     *
     * Set<ProjectStatus> is a generic type: the angle brackets promise the compiler that only
     * ProjectStatus values can go in. Without them the field would be a raw Set, and
     * DRAFT.successors.add("ACTIVE") - a String, not a status - would compile happily and
     * then make canTransitionTo() compare apples with pears for ever.
     *
     * The field is private and has no getter on purpose. The set is mutable, so handing it
     * out would let any caller write DRAFT.successors.add(COMPLETED) and quietly rewrite the
     * life cycle of the whole application at run time. Callers get the read-only question
     * canTransitionTo() instead.
     */
    private Set<ProjectStatus> successors;

    /*
     * A static block: code that runs once, when the class is first loaded, after the five
     * constants above have been built.
     *
     * WHY NOT PASS THE SET TO A CONSTRUCTOR, WHICH WOULD BE THE OBVIOUS WAY:
     * an enum constant cannot name another constant of the same enum in its own constructor
     * arguments - Java rejects it with "illegal forward reference", because DRAFT is built
     * before ACTIVE exists. Filling the sets afterwards, here, is the standard way round it.
     *
     * WARNING FOR ANYONE ADDING A SIXTH STATUS: it must be given a line in this block. A
     * constant left out keeps successors at null, and the first call to canTransitionTo()
     * on it throws NullPointerException instead of answering false.
     */
    static {
        // From DRAFT the project can start, or be dropped before it ever starts.
        // ON_HOLD is absent on purpose: a project that never started cannot be paused.
        DRAFT.successors     = EnumSet.of(ACTIVE, CANCELLED);
        // ACTIVE is the hub: it can be paused, finished, or stopped for good.
        ACTIVE.successors    = EnumSet.of(ON_HOLD, COMPLETED, CANCELLED);
        // A paused project resumes or is stopped for good. COMPLETED is absent on purpose:
        // a project has to be running again before it can be declared finished.
        ON_HOLD.successors   = EnumSet.of(ACTIVE, CANCELLED);
        // End states. An empty set is used rather than leaving the field at null, because
        // canTransitionTo() calls successors.contains(...) without checking for null first:
        // with null, asking whether a finished project can be reopened would crash the
        // request with NullPointerException instead of returning false and producing the
        // clean "transition not allowed" error message.
        COMPLETED.successors = EnumSet.noneOf(ProjectStatus.class);
        CANCELLED.successors = EnumSet.noneOf(ProjectStatus.class);
        // EnumSet, and not HashSet: EnumSet stores the members as bits in a single long, so
        // contains() is one bit test with no hashing, and it physically cannot hold anything
        // other than a ProjectStatus. With a HashSet the same code would work but allocate
        // much more for a set that never holds more than five values.
    }

    /**
     * Answers "may this project move from its current status to the one asked for?".
     * Returns true when the move is allowed, false when it is not.
     *
     * Called by ProjectService.changeStatus(), which turns a false into a
     * BusinessRuleException and an HTTP 400, so the caller sees a clear message rather than
     * a silent refusal.
     *
     * WHY "this == target" COMES FIRST:
     * it lets a caller re-send the status the project already has without getting an error.
     * Why that is needed: the status control on the projects screen sends the chosen value
     * on every change, and a double click, a page refresh or a retried request would
     * otherwise fail with "transition not allowed: COMPLETED -> COMPLETED" even though
     * nothing was actually asked for. Repeating the same call now simply changes nothing.
     *
     * Note that the check is deliberately not symmetric: the answer depends on the status
     * the project is in right now, which is why this is an instance method on the enum and
     * not a static helper taking two arguments.
     */
    public boolean canTransitionTo(ProjectStatus target) {
        return this == target || successors.contains(target);
    }
}

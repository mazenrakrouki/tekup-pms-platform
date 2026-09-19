package com.pms.project.service;

import com.pms.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/*
 * WHAT THIS FILE IS
 * -----------------
 * The one place that answers the question "WHICH projects is the person making this request
 * allowed to touch?".
 *
 * PMS separates two different questions (this is ADR-021):
 *   - permission: "may this person read projects at all?"  -> answered by @PreAuthorize on the
 *     service methods of the other classes, for example hasAuthority('VIEW_PROJECT');
 *   - scope:      "on WHICH projects?"                     -> answered here.
 * A project manager holds VIEW_PROJECT (permission) but only for the projects he manages
 * (scope). Permission alone is NOT enough, and that is exactly the gap this class closes.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   HTTP request
 *     -> ProjectScopeInterceptor (com.pms.shared.config) for every URL that looks like
 *        /api/projects/{id} or /api/projects/{id}/anything: it calls assertCanAccess() below
 *        BEFORE the controller method even starts;
 *     -> ProjectService (findAll, the paged findAll, findArchived, findById, archive,
 *        unarchive) also calls this class, to filter the lists - whose URLs carry no id, so
 *        the interceptor cannot protect them - and to double-check the single reads;
 *     -> this class -> ProjectRepository.findAccessibleProjectIdsByEmail(email)
 *        -> one SQL query over "projects" plus an EXISTS over "team_assignments".
 * It calls nothing else. It reads the logged-in person from the Spring Security context and
 * reads the database. It never writes.
 *
 * WHAT THE PERIMETER QUERY DOES AND DOES NOT RETURN - READ THIS BEFORE A JURY DOES
 * -------------------------------------------------------------------------------
 * findAccessibleProjectIdsByEmail filters on "p.deleted = false AND p.archived = false". So
 * the set returned by accessibleProjectIds() never holds the id of an ARCHIVED project, even
 * when the person is its chef de projet. Two visible consequences, both of them real:
 *   - ProjectService.findArchived() filters its list with that set, so a caller without
 *     VIEW_ALL_PROJECTS always gets an empty "archived projects" tab;
 *   - assertCanAccess() below therefore refuses PATCH /api/projects/{id}/unarchive for that
 *     same caller, who gets 403 on a project he himself archived a minute earlier.
 * Only the holder of VIEW_ALL_PROJECTS escapes this, because hasAllAccess() exits before the
 * query runs. This is a side effect of the "archived = false" condition, not a rule anybody
 * wrote down; it is listed as an open point rather than hidden here.
 *
 * WHY IT EXISTS
 * -------------
 * Without it, every single query in the application would have to remember to add its own
 * "...AND this project belongs to me" condition. One forgotten condition in one query is a
 * data leak: project manager A opens /api/projects/7/devis-interne and reads the budget, the
 * margin and the cost prices of project 7, which belongs to project manager B.
 * Because the rule lives here and is called from an interceptor, a new controller added
 * tomorrow under /api/projects/{id}/... is protected the day it is written, with no extra code.
 *
 * WHY IT NEVER LOOKS AT A ROLE NAME
 * ---------------------------------
 * The scope is built from a capability (VIEW_ALL_PROJECTS) plus real relationships in the
 * database: being the chef de projet of the project, or being an active member of its team.
 * It never tests something like "is this person a DIRECTOR". This follows ADR-001: roles and
 * their permissions are rows in the database that an administrator edits at run time. If the
 * code tested the word "DIRECTOR", giving the same portfolio-wide view to a new role tomorrow
 * would need a code change, a rebuild and a redeploy; as written, it needs one row in the
 * role_permissions table.
 */
@Service
// @Service marks this class as a Spring bean, so Spring builds ONE shared instance at start-up
// and injects it where it is needed (ProjectService, ProjectScopeInterceptor).
// Without it those two classes fail to start with "no qualifying bean of type
// ProjectScopeService", because nobody would ever build the object.
@RequiredArgsConstructor
// Lombok writes the constructor over the final field below, and Spring passes the repository
// into it. Without it we would hand-write that constructor, and ProjectScopeServiceTest could
// not inject a mock repository as easily.
public class ProjectScopeService {

    /**
     * The name of the capability that lifts the scope filter completely: whoever holds it sees
     * the whole portfolio. In the default matrix the Director holds it (added by the Flyway
     * migration V13), but that is a fact stored in the database, not a fact about this code.
     *
     * Why a named constant rather than the text typed in several places: the very same text is
     * stored in the "permissions" table. A typo in one copy ("VIEW_ALL_PROJECT" without the S)
     * would silently make hasAllAccess() always return false, and the Director would quietly
     * lose most of his projects with no error message anywhere. One constant means one place
     * to be right.
     *
     * It is declared public so that another class could name the capability through this one
     * constant instead of typing the text again. Be exact in front of a jury: today nothing
     * outside this file uses it. ProjectScopeServiceTest still writes the literal
     * "VIEW_ALL_PROJECTS" in its SimpleGrantedAuthority, so the test would keep passing even if
     * the constant were mistyped - the protection described above covers this class only.
     */
    public static final String ALL_ACCESS = "VIEW_ALL_PROJECTS";

    private final ProjectRepository projectRepository;

    /**
     * Answers "does the person making the current request see every project?".
     * Returns true when the capability VIEW_ALL_PROJECTS is present, and false otherwise,
     * including when nobody is logged in.
     *
     * Why it reads the security context instead of taking the user as an argument: every
     * caller is already inside an HTTP request handled by Spring Security, so the answer is
     * already there. Passing a user in would let a caller pass somebody else by mistake, and
     * the check would then be about the wrong person.
     */
    public boolean hasAllAccess() {
        // SecurityContextHolder holds the authenticated user of the CURRENT request thread,
        // put there by the JWT filter when the request came in. Authentication carries the
        // login (getName()) and the list of capabilities.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Null happens on a public endpoint (login, token refresh) and in a unit test that did
        // not set a user. Answering false instead of crashing is the safe answer: no user
        // means no portfolio-wide access.
        // Without this line such a call throws NullPointerException, and the client gets an
        // unhelpful HTTP 500 instead of the 401 the security layer is about to produce.
        if (auth == null) return false;
        // The stream reads the capability list of the user, turns each entry into its plain
        // text code, and stops at the first one equal to "VIEW_ALL_PROJECTS".
        // anyMatch stops on the first hit instead of walking the whole list.
        // ALL_ACCESS::equals is written this way round on purpose: the constant is never null,
        // so the comparison cannot throw even if some authority returned a null code.
        // Written the other way, a.equals(ALL_ACCESS), that row would throw
        // NullPointerException and break every request of that user.
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ALL_ACCESS::equals);
    }

    /**
     * Returns the ids of every project this e-mail address may work on: the projects where the
     * person is the chef de projet, plus the projects where the person is an active team
     * member. Returns an empty set when the person has neither, and the callers read an empty
     * set as "sees nothing".
     *
     * Marker M-6, and this is the whole point of the method. The first version asked three
     * questions in a row: find the user by e-mail, then find the projects he leads, then find
     * his team assignments. That was three round trips to the database on EVERY request going
     * through ProjectScopeInterceptor. It is now one single SQL query using an EXISTS
     * sub-query (ProjectRepository.findAccessibleProjectIdsByEmail).
     *
     * Why a Set<Long> of ids and not a list of Project objects: the callers only ever ask
     * "is project 7 in there?" or filter a list with it. A Set answers contains() in roughly
     * one step whatever its size, and loading the id column alone avoids dragging whole
     * project rows, budgets included, through memory on every request.
     */
    // readOnly = true opens one read-only transaction around the query.
    // Why: Hibernate then skips keeping a copy of each row for change detection, and the
    // database driver can take its read path. Nothing here is ever written.
    // Without it the query still works, but it runs with the default read-write settings and
    // Hibernate does the extra bookkeeping it does for a transaction that may write.
    @Transactional(readOnly = true)
    public Set<Long> accessibleProjectIds(String email) {
        // A null e-mail means there is no identified user (anonymous request, or a test with
        // an empty security context). Set.of() is the immutable empty set, so the caller sees
        // zero accessible projects: the closed, safe answer.
        // Without this guard the query runs with email = null, every comparison with null in
        // SQL answers "unknown", and the result is empty anyway - but only after a useless
        // trip to the database on every anonymous request.
        if (email == null) return Set.of();
        return projectRepository.findAccessibleProjectIdsByEmail(email);
    }

    /**
     * The guard used before acting on one precise project. It returns quietly when the project
     * is inside the caller's scope, and throws AccessDeniedException when it is not. Spring
     * Security turns that exception into an HTTP 403 (Forbidden).
     *
     * Called by ProjectScopeInterceptor for every /api/projects/{id}/** URL, and again inside
     * ProjectService.findById / archive / unarchive.
     *
     * Why it throws instead of returning true or false: a boolean can be ignored. Someone
     * writing a new method could call it and forget to test the answer, and the check would
     * silently do nothing. An exception cannot be forgotten - either the caller continues, or
     * the request stops here.
     *
     * Why the id is checked again even though it comes from the URL: that id is supplied by
     * the browser and can be edited by hand. Without this check, changing /api/projects/4 into
     * /api/projects/9 in the address bar opens project 9. The attack has a name, IDOR
     * (Insecure Direct Object Reference), and this method is what closes it.
     */
    @Transactional(readOnly = true)
    public void assertCanAccess(Long projectId, String email) {
        // Fast exit for the portfolio-wide capability: no filter applies, so there is no point
        // querying the database at all. It also keeps the Director's requests at zero extra SQL.
        if (hasAllAccess()) return;
        if (!accessibleProjectIds(email).contains(projectId)) {
            // The message stays in French because it reaches the user interface, and the whole
            // application speaks French to its users. It means "project outside your perimeter".
            throw new AccessDeniedException("Projet hors périmètre");
        }
    }
}

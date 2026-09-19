package com.pms.governance.service;

import com.pms.governance.dto.RiskRequest;
import com.pms.governance.dto.RiskResponse;
import com.pms.governance.entity.Risk;
import com.pms.governance.mapper.RiskMapper;
import com.pms.governance.repository.RiskRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * WHAT THIS FILE IS
 * -----------------
 * The business service of the risk register of a project. A "risk" is something that
 * may go wrong later. One risk carries a description, how likely it is (probabilite),
 * how much damage it would do (impact), the plan to reduce it (planMitigation), and a
 * status: OUVERT (still open), MITIGE (reduced) or FERME (closed).
 * One Risk object = one row of the PostgreSQL table "risks", created by migration V11.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   Angular GovernanceService (core/services/governance.service.ts)
 *     -> HTTP /api/projects/{projectId}/risks
 *     -> ProjectScopeInterceptor  (ADR-021: may this caller act on THIS project?)
 *     -> RiskController           (reads the URL, calls this file, returns the DTO)
 *     -> RiskService              (THIS FILE: permission + transaction + rules)
 *     -> RiskRepository           (the only class that talks to the "risks" table)
 *     -> RiskMapper               (Risk entity -> RiskResponse, the JSON sent back)
 * RiskController is the only caller in the whole backend; no other service uses it.
 *
 * WHY IT EXISTS - what would be missing if you deleted it
 * -------------------------------------------------------
 * This class holds the four things the controller must NOT hold:
 *   1. the permission check (VIEW_GOVERNANCE to read, MANAGE_GOVERNANCE to write);
 *   2. the transaction boundary around each operation;
 *   3. the "does risk 42 really belong to project 7?" check, in loadRisk();
 *   4. the soft delete (deleted = true) instead of a real SQL DELETE.
 * Delete this class and the controller would have to call the repository directly:
 * any logged-in account could then read the risk register of any project, and a
 * delete would erase the row for good, leaving no trace for the audit.
 *
 * HOW IT RELATES TO THE THREE OTHER FILES IN THIS FOLDER
 * -----------------------------------------------------
 * LivrableService, PartiePrenanteService and DemandeChangementService use exactly the
 * same skeleton and guard the same two permissions; the governance screen shows the
 * four of them as four tabs of one project. The only real difference is the state
 * machine: a deliverable and a change request refuse some operations depending on
 * their current status, while a risk and a stakeholder can always be edited.
 */

/**
 * WHAT IT DOES
 * Offers the four operations of the risk register of one project: list, create,
 * update, delete. Every public method takes the projectId read from the URL as its
 * first argument, and gives back a RiskResponse DTO (Data Transfer Object: a small
 * object built only to travel to the browser) - never the Risk entity itself, so the
 * database shape never leaks into the API.
 *
 * WHY WRITTEN THIS WAY RATHER THAN THE OBVIOUS ALTERNATIVE
 * The obvious alternative is to put these checks in RiskController. They are here
 * because @PreAuthorize is applied by a Spring proxy placed around this bean: on the
 * service, the check runs for EVERY caller of the method, not only for the HTTP
 * route. If tomorrow another service calls riskService.delete(...), it is still
 * checked. A check written on the controller would be walked around by that call.
 */
// @Service registers this class as a Spring bean: the framework builds one shared
// instance at startup and injects it into RiskController.
// It is also what lets Spring wrap the bean in a proxy, and that proxy is what really
// applies @PreAuthorize and @Transactional below.
// Without it: the application fails to start with "no qualifying bean of type
// RiskService", and a hand-made `new RiskService(...)` would run with no permission
// check and no transaction at all.
@Service
// Lombok writes, at compile time, a constructor that takes the three `final` fields
// below, and Spring uses that single constructor to inject them.
// Why constructor injection rather than @Autowired on each field: the fields can stay
// final, so nothing can swap the repository at runtime, and a unit test can build the
// service with mock objects in one line.
// Without it: only the default empty constructor exists, the three fields stay null,
// and the very first call ends in a NullPointerException.
@RequiredArgsConstructor
public class RiskService {

    // The three collaborators, injected once at startup:
    //   riskRepository    - reads and writes the "risks" table, soft-delete aware;
    //   projectRepository - used only to prove that the project of the URL exists;
    //   riskMapper        - MapStruct-generated translator Risk -> RiskResponse.
    // `final` means each one is set by the constructor and can never be replaced, so no
    // later code can silently point this service at a different table.
    private final RiskRepository    riskRepository;
    private final ProjectRepository projectRepository;
    private final RiskMapper        riskMapper;

    /**
     * Returns every risk of one project that is not soft-deleted, newest first.
     *
     * WHY WRITTEN THIS WAY
     * loadProject(projectId) is called for one reason only: the 404 it can throw. The
     * list query alone would happily return an empty list for a project id that does
     * not exist, and the screen would say "no risk yet" instead of "no such project".
     */
    // Checks that the logged-in user carries the VIEW_GOVERNANCE permission. Spring
    // reads this annotation because @EnableMethodSecurity is set on SecurityConfig.
    // Why a permission and never a role name: roles and their permissions live in the
    // database (table role_permissions, rebuilt by migration V12) and an administrator
    // can change them at runtime from the Roles screen. Writing hasRole('CHEF_PROJET')
    // would freeze that matrix inside the Java code and make the admin screen a lie.
    // This is only half of the protection: the permission says "this user may read
    // governance data somewhere", ProjectScopeInterceptor (ADR-021) says "on THIS
    // project". Without the interceptor, a manager holding VIEW_GOVERNANCE could read
    // the risk register of a project he does not manage.
    // Without this line: any authenticated account, a developer for instance, could
    // list the risks of any project.
    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    // Runs the whole method inside one read-only database transaction.
    // Why: the entities stay attached while the mapper reads them, and Hibernate skips
    // the change tracking it would do in a writable transaction.
    // Without it: an accidental setter in the read path could be written to the
    // database at the end of the method, and each query could run on its own
    // connection, so one list could mix rows read at two different moments.
    @Transactional(readOnly = true)
    public List<RiskResponse> findByProject(Long projectId) {
        // Called for its side effect only: it throws NotFoundException, which the global
        // handler turns into HTTP 404, when the project id of the URL does not exist or
        // was soft-deleted. The Project it returns is not needed here.
        loadProject(projectId);
        // findActiveByProjectId adds "AND deleted = false" and JOIN FETCHes the project in
        // the same SQL query, so the mapper can read project.getCode() without one extra
        // SELECT per risk. Without that fetch, 50 risks would mean 51 database round
        // trips - the classic "N+1 queries" problem.
        return riskMapper.toResponseList(riskRepository.findActiveByProjectId(projectId));
    }

    /**
     * Adds one risk to the register of a project and returns it with its new id.
     *
     * WHY WRITTEN THIS WAY
     * The project is loaded first and attached to the new Risk, so the foreign key
     * project_id can never point at a project that does not exist. The id comes from
     * the URL, and RiskRequest has no projectId field at all: that is what stops a
     * caller from passing the ADR-021 scope check with a project he owns and then
     * filing the risk against somebody else's project.
     */
    // MANAGE_GOVERNANCE, not VIEW_GOVERNANCE: this method writes a row.
    // The two are separate rows of the permissions table. Following the authorization
    // matrix (UC-22 / UC-23), the project manager role holds both, while the director
    // holds VIEW_GOVERNANCE only.
    // Without this line: a director, who is only meant to watch the portfolio, could
    // add risks on every project he can see.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work: the SELECT on projects and the INSERT into risks either both
    // happen or neither does, and other users see the new row only once the method
    // returns without an exception.
    // Without it, save() would open its own small transaction and commit at once, so a
    // failure later in the method would still leave the risk row behind.
    @Transactional
    public RiskResponse create(Long projectId, RiskRequest request) {
        Project project = loadProject(projectId);
        // Lombok's @Builder on the entity: every value is named, so adding a column later
        // cannot silently shift positional constructor arguments.
        // `statut` is taken from the request here, unlike a deliverable or a change
        // request where the server alone decides it. That is deliberate: the risk
        // register is written by hand and a risk can legitimately be recorded as already
        // MITIGE; there is no approval step to protect here. The entity default (OUVERT)
        // never applies on this path, because RiskRequest.statut is @NotNull and is
        // therefore always provided.
        // The id, the dates and the deleted flag are absent on purpose: they come from
        // BaseEntity and are filled by the database and by JPA auditing.
        Risk risk = Risk.builder()
                .project(project)
                .description(request.description())
                .probabilite(request.probabilite())
                .impact(request.impact())
                .planMitigation(request.planMitigation())
                .statut(request.statut())
                .build();
        // save() gives back the instance that now carries the id generated by Postgres
        // (BIGSERIAL). That returned instance is the one being mapped, so the browser
        // receives the id it needs for a later edit or delete. Mapping the local `risk`
        // variable instead would send back id = null and the row could never be reached.
        return riskMapper.toResponse(riskRepository.save(risk));
    }

    /**
     * Overwrites the five editable fields of one risk and returns the updated DTO.
     *
     * WHY WRITTEN THIS WAY
     * It reloads the row from the database through loadRisk() instead of trusting an
     * object sent by the client. Only these five fields can change: the project, the id
     * and the audit columns of BaseEntity are never touched, so a caller cannot move a
     * risk to another project by sending a different project id in the body.
     * Unlike a deliverable or a change request, a risk has no frozen state: even a
     * closed risk (FERME) can still be corrected, because the register is a living
     * document that is re-read and cleaned up at the end of the project.
     */
    // Same write permission as create(): editing a risk changes the governance record
    // of the project, so a read-only account must not be able to do it.
    // Without this line: any holder of VIEW_GOVERNANCE could rewrite the impact of a
    // risk, and the risk matrix shown to the steering committee would be unreliable.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work around a read-then-write sequence: loadRisk() SELECTs the row and
    // the setters below produce the UPDATE.
    // Without it, the row could be read in one transaction and written in another; a
    // second user saving the same risk in between would have his change overwritten
    // without anyone noticing.
    @Transactional
    public RiskResponse update(Long projectId, Long id, RiskRequest request) {
        // loadRisk does two things: it finds the non-deleted row, and it refuses the call
        // when that row belongs to another project. See the method at the bottom.
        Risk risk = loadRisk(id, projectId);
        // The entity is managed by Hibernate inside the transaction, so these setters are
        // already enough on their own: the UPDATE statement is written at commit time.
        // The explicit save() below is therefore redundant but harmless; it is kept so
        // that "this method writes" stays visible when reading the code.
        // Note that every field is overwritten, including planMitigation: a PUT replaces
        // the whole risk. Sending a body without planMitigation empties that column, it
        // does not keep the old text.
        risk.setDescription(request.description());
        risk.setProbabilite(request.probabilite());
        risk.setImpact(request.impact());
        risk.setPlanMitigation(request.planMitigation());
        risk.setStatut(request.statut());
        return riskMapper.toResponse(riskRepository.save(risk));
    }

    /**
     * Soft-deletes one risk: the row stays in the table, with deleted = true. Returns
     * nothing, and the controller answers 204 No Content.
     *
     * WHY A FLAG AND NOT A REAL SQL DELETE
     * A risk is a governance record. The audit trail must still show that it existed
     * and who removed it, which the created_by / updated_by / updated_at columns of
     * BaseEntity keep (columns added by migration V19). Every read in this package goes
     * through a findActive...() query that adds "AND deleted = false", so a flagged row
     * disappears from every screen while staying available for an audit.
     * With a real DELETE, a wrong click would be final and nothing could be restored.
     */
    // Write permission again: removing a risk from the register is the most sensitive
    // of the three write operations, because the risk simply stops being reported.
    // Without this line: a read-only account could empty the whole risk register.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // The read and the flag update are one unit of work.
    // Without it, loadRisk() and the save() would run in two transactions, and a crash
    // in between would leave the request answered as "deleted" while the row is still
    // visible on the screen after a refresh.
    @Transactional
    public void delete(Long projectId, Long id) {
        Risk risk = loadRisk(id, projectId);
        // The deleted flag lives on BaseEntity, the parent class shared by every entity of
        // the project, so the same soft-delete rule applies in the whole application.
        risk.setDeleted(true);
        riskRepository.save(risk);
    }

    /**
     * Loads one non-deleted risk AND proves that it belongs to the project of the URL.
     * Gives back the managed entity, or throws NotFoundException (HTTP 404).
     *
     * WHY WRITTEN THIS WAY - the second half is the important one
     * ProjectScopeInterceptor (ADR-021) has already checked that the caller may act on
     * project 7. It did NOT check that risk 42 belongs to project 7, because it only
     * reads the URL. Without the comparison below, a manager of project 7 could call
     * DELETE /api/projects/7/risks/42 and delete a risk of project 9: the URL passes
     * the scope check, and the id would simply be looked up on its own.
     *
     * WHY IT ANSWERS 404 AND NOT 403 ON A MISMATCH
     * The message is exactly the same in both cases, on purpose. A 403 would tell the
     * caller "this id exists, only not here", which is already information about
     * another project. A 404 says nothing.
     *
     * WHY PRIVATE
     * It is an internal helper, so it is not wrapped by the security proxy and carries
     * no @PreAuthorize. That is correct here: the two methods that call it, update()
     * and delete(), have already checked MANAGE_GOVERNANCE before reaching this point.
     */
    private Risk loadRisk(Long id, Long projectId) {
        // findActiveById returns Optional<Risk>. Optional is the standard Java way to say
        // "there may be no value here", so the empty case has to be handled on the spot.
        // orElseThrow turns it into the 404 straight away, instead of a null that would
        // explode a few lines later as a NullPointerException with no useful message.
        Risk risk = riskRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Risque introuvable : " + id));
        // The ownership check described above.
        // equals() and not == : both sides are Long objects, and == would compare object
        // references. Java caches only the small Long values, so == would look perfectly
        // correct in a test using id 5, and would start failing silently once the ids
        // pass 127 - the worst kind of bug, because it appears only in production.
        if (!risk.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Risque introuvable : " + id);
        }
        return risk;
    }

    /**
     * Loads the project named in the URL, or throws NotFoundException (HTTP 404).
     *
     * WHY findActiveById AND NOT THE BUILT-IN findById
     * findActiveById adds "AND deleted = false". A soft-deleted project must behave
     * exactly like a project that never existed; without this, new risks could still be
     * attached to a project that disappeared from every screen months ago.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}

package com.pms.governance.service;

import com.pms.governance.dto.PartiePrenanteRequest;
import com.pms.governance.dto.PartiePrenanteResponse;
import com.pms.governance.entity.PartiePrenante;
import com.pms.governance.mapper.PartiePrenanteMapper;
import com.pms.governance.repository.PartiePrenanteRepository;
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
 * The business service of the stakeholder register ("parties prenantes") of a project.
 * A stakeholder is a person concerned by the project: the client sponsor, a department
 * head, an external auditor. Each one carries a name, a job title, an e-mail, a phone
 * number, and two levels taken from the same three-value scale FAIBLE / MOYEN / ELEVE:
 *   - influence : how much power this person has over the project;
 *   - interet   : how much this person cares about the result.
 * Those two levels are what feeds the classic influence/interest grid used to decide
 * who must be informed, consulted, or simply kept satisfied.
 * One PartiePrenante object = one row of the table "parties_prenantes" (migration V11).
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   Angular GovernanceService (core/services/governance.service.ts)
 *     -> HTTP /api/projects/{projectId}/parties-prenantes
 *     -> ProjectScopeInterceptor  (ADR-021: may this caller act on THIS project?)
 *     -> PartiePrenanteController (reads the URL, calls this file, returns the DTO)
 *     -> PartiePrenanteService    (THIS FILE: permission + transaction + ownership)
 *     -> PartiePrenanteRepository (the only class that talks to "parties_prenantes")
 *     -> PartiePrenanteMapper     (entity -> PartiePrenanteResponse, the JSON sent back)
 * PartiePrenanteController is the only caller in the backend.
 *
 * WHY IT EXISTS - what would be missing if you deleted it
 * -------------------------------------------------------
 * Three guarantees would disappear with it:
 *   1. the permission check (VIEW_GOVERNANCE to read, MANAGE_GOVERNANCE to write);
 *   2. the ownership check of loadPP(): "does stakeholder 42 belong to project 7?";
 *   3. the soft delete, which keeps the row for the audit instead of erasing it.
 * The contact details stored here are personal data, so the read permission is not a
 * detail: without it any logged-in account could download the e-mail and the phone
 * number of the client's management team.
 *
 * HOW IT RELATES TO THE THREE OTHER FILES IN THIS FOLDER
 * -----------------------------------------------------
 * This is the simplest of the four governance services: it is the only one with NO
 * state machine, so it never throws BusinessRuleException and it does not import it.
 * A stakeholder can always be edited or removed, while LivrableService and
 * DemandeChangementService refuse some operations once a status has been reached.
 * RiskService is the closest twin: same four methods, same two helpers at the bottom.
 */

/**
 * WHAT IT DOES
 * Offers the four operations of the stakeholder register of one project: list, create,
 * update, delete. Every public method takes the projectId read from the URL as its
 * first argument and gives back a PartiePrenanteResponse DTO (Data Transfer Object: a
 * small object built only to travel to the browser), never the entity itself.
 *
 * WHY WRITTEN THIS WAY RATHER THAN THE OBVIOUS ALTERNATIVE
 * The obvious alternative is to check the permission in PartiePrenanteController. It
 * is done here because @PreAuthorize is applied by a Spring proxy around this bean: on
 * the service, the check protects EVERY caller of the method, not only the HTTP route.
 * The day another service calls partiePrenanteService.delete(...), it is still checked.
 */
// @Service registers this class as a Spring bean: one shared instance is built at
// startup and injected into PartiePrenanteController.
// It is also what lets Spring wrap the bean in a proxy, and that proxy is what really
// applies @PreAuthorize and @Transactional below.
// Without it: the application fails to start with "no qualifying bean of type
// PartiePrenanteService", and a hand-made instance would run with no permission check
// and no transaction at all.
@Service
// Lombok writes, at compile time, a constructor taking the three `final` fields below,
// and Spring uses that single constructor to inject them.
// Why constructor injection rather than @Autowired on each field: the fields stay
// final, so nothing can swap the repository at runtime, and a unit test can build the
// service with mock objects in one line.
// Without it: only the default empty constructor exists, the three fields stay null,
// and the very first call ends in a NullPointerException.
@RequiredArgsConstructor
public class PartiePrenanteService {

    // The three collaborators, injected once at startup:
    //   ppRepository      - reads and writes "parties_prenantes", soft-delete aware;
    //   projectRepository - used only to prove that the project of the URL exists;
    //   ppMapper          - MapStruct translator PartiePrenante -> PartiePrenanteResponse.
    // `final` means each one is set by the constructor and can never be replaced, so no
    // later code can silently point this service at a different table.
    private final PartiePrenanteRepository ppRepository;
    private final ProjectRepository        projectRepository;
    private final PartiePrenanteMapper     ppMapper;

    /**
     * Returns every stakeholder of one project that is not soft-deleted, ordered by name.
     *
     * WHY WRITTEN THIS WAY
     * loadProject(projectId) is called for one reason only: the 404 it can throw. The
     * list query alone would return an empty list for a project id that does not exist,
     * and the screen would say "no stakeholder yet" instead of "no such project".
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
    // the stakeholder list of a project he does not manage.
    // Without this line: any authenticated account could download the names, e-mails
    // and phone numbers of the client's management team.
    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    // Runs the whole method inside one read-only database transaction.
    // Why: the entities stay attached while the mapper reads them, and Hibernate skips
    // the change tracking it would do in a writable transaction.
    // Without it: an accidental setter in the read path could be written to the
    // database at the end of the method, and each query could run on its own
    // connection, so one list could mix rows read at two different moments.
    @Transactional(readOnly = true)
    public List<PartiePrenanteResponse> findByProject(Long projectId) {
        // Called for its side effect only: it throws NotFoundException, which the global
        // handler turns into HTTP 404, when the project id of the URL does not exist or
        // was soft-deleted. The Project it returns is not needed here.
        loadProject(projectId);
        // findActiveByProjectId adds "AND deleted = false" and JOIN FETCHes the project in
        // the same SQL query, so the mapper can read project.getCode() without one extra
        // SELECT per stakeholder. Without that fetch, 30 stakeholders would mean 31
        // database round trips - the classic "N+1 queries" problem.
        return ppMapper.toResponseList(ppRepository.findActiveByProjectId(projectId));
    }

    /**
     * Adds one stakeholder to the register of a project and returns it with its new id.
     *
     * WHY WRITTEN THIS WAY
     * The project is loaded first and attached to the new row, so the foreign key
     * project_id can never point at a project that does not exist. The id comes from
     * the URL, and PartiePrenanteRequest has no projectId field at all: that is what
     * stops a caller from passing the ADR-021 scope check with a project he owns and
     * then attaching the stakeholder to somebody else's project.
     *
     * Note there is no duplicate check: the same person can be added twice. This is on
     * purpose, because two different people can share a name, and the register is a
     * working document the project manager cleans up himself.
     */
    // MANAGE_GOVERNANCE, not VIEW_GOVERNANCE: this method writes a row.
    // The two are separate rows of the permissions table. Following the authorization
    // matrix (UC-22 / UC-23), the project manager role holds both, while the director
    // holds VIEW_GOVERNANCE only.
    // Without this line: a director, who is only meant to watch the portfolio, could
    // add stakeholders to every project he can see.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work: the SELECT on projects and the INSERT into parties_prenantes
    // either both happen or neither does, and other users see the new row only once the
    // method returns without an exception.
    // Without it, save() would open its own small transaction and commit at once, so a
    // failure later in the method would still leave the stakeholder row behind.
    @Transactional
    public PartiePrenanteResponse create(Long projectId, PartiePrenanteRequest request) {
        Project project = loadProject(projectId);
        // Lombok's @Builder on the entity: every value is named, so adding a column later
        // cannot silently shift positional constructor arguments.
        // influence and interet are always provided, because both are @NotNull in
        // PartiePrenanteRequest; the entity defaults (MOYEN) therefore never apply on
        // this path. They are enums, not free text: a value such as "HIGH" is refused by
        // Jackson with a 400, before it can reach the database check constraint
        // chk_pp_influence of migration V11 and come back as an unreadable 500.
        // The id, the dates and the deleted flag are absent on purpose: they come from
        // BaseEntity and are filled by the database and by JPA auditing.
        PartiePrenante pp = PartiePrenante.builder()
                .project(project)
                .nom(request.nom())
                .fonction(request.fonction())
                .email(request.email())
                .telephone(request.telephone())
                .influence(request.influence())
                .interet(request.interet())
                .build();
        // save() gives back the instance that now carries the id generated by Postgres
        // (BIGSERIAL). That returned instance is the one being mapped, so the browser
        // receives the id it needs for a later edit or delete. Mapping the local `pp`
        // variable instead would send back id = null and the row could never be reached.
        return ppMapper.toResponse(ppRepository.save(pp));
    }

    /**
     * Overwrites the six editable fields of one stakeholder and returns the updated DTO.
     *
     * WHY WRITTEN THIS WAY
     * It reloads the row from the database through loadPP() instead of trusting an
     * object sent by the client. Only these six fields can change: the project, the id
     * and the audit columns of BaseEntity are never touched, so a caller cannot move a
     * stakeholder to another project by sending a different project id in the body.
     * There is no status check before writing, unlike a deliverable or a change
     * request: a contact can always be corrected, because people change job and phone
     * number while the project runs.
     */
    // Same write permission as create(): a stakeholder card is part of the governance
    // record of the project.
    // Without this line: any holder of VIEW_GOVERNANCE could rewrite the e-mail of the
    // client sponsor, and the project reports would be sent to the wrong address.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work around a read-then-write sequence: loadPP() SELECTs the row and
    // the setters below produce the UPDATE.
    // Without it, the row could be read in one transaction and written in another; a
    // second user saving the same stakeholder in between would have his change
    // overwritten without anyone noticing.
    @Transactional
    public PartiePrenanteResponse update(Long projectId, Long id, PartiePrenanteRequest request) {
        // loadPP does two things: it finds the non-deleted row, and it refuses the call
        // when that row belongs to another project. See the method at the bottom.
        PartiePrenante pp = loadPP(id, projectId);
        // The entity is managed by Hibernate inside the transaction, so these setters are
        // already enough on their own: the UPDATE statement is written at commit time.
        // The explicit save() below is therefore redundant but harmless; it is kept so
        // that "this method writes" stays visible when reading the code.
        // Note that every field is overwritten, including the optional ones. A PUT
        // replaces the whole stakeholder: a body sent without "telephone" empties that
        // column, it does not keep the previous number.
        pp.setNom(request.nom());
        pp.setFonction(request.fonction());
        pp.setEmail(request.email());
        pp.setTelephone(request.telephone());
        pp.setInfluence(request.influence());
        pp.setInteret(request.interet());
        return ppMapper.toResponse(ppRepository.save(pp));
    }

    /**
     * Soft-deletes one stakeholder: the row stays in the table, with deleted = true.
     * Returns nothing, and the controller answers 204 No Content.
     *
     * WHY A FLAG AND NOT A REAL SQL DELETE
     * The stakeholder register is part of the project record. The audit trail must
     * still show who was on the list and who removed the entry, which the created_by /
     * updated_by / updated_at columns of BaseEntity keep (columns added by migration
     * V19). Every read in this package goes through a findActive...() query that adds
     * "AND deleted = false", so a flagged row disappears from every screen while
     * staying available for an audit.
     * With a real DELETE, a wrong click would be final and nothing could be restored.
     *
     * Unlike LivrableService.delete() and DemandeChangementService.delete(), there is
     * no status to check first: a stakeholder has no lifecycle, so the row can always
     * be flagged.
     */
    // Write permission again: removing a stakeholder means the person stops receiving
    // the project communication.
    // Without this line: a read-only account could empty the stakeholder register.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // The read and the flag update are one unit of work.
    // Without it, loadPP() and the save() would run in two transactions, and a crash in
    // between would leave the request answered as "deleted" while the row is still
    // visible on the screen after a refresh.
    @Transactional
    public void delete(Long projectId, Long id) {
        PartiePrenante pp = loadPP(id, projectId);
        // The deleted flag lives on BaseEntity, the parent class shared by every entity of
        // the project, so the same soft-delete rule applies in the whole application.
        // Be aware that the personal data (name, e-mail, phone) stays in the row; only
        // the flag changes. A real erasure request has to be handled outside this path.
        pp.setDeleted(true);
        ppRepository.save(pp);
    }

    /**
     * Loads one non-deleted stakeholder AND proves it belongs to the project of the URL.
     * Gives back the managed entity, or throws NotFoundException (HTTP 404).
     *
     * WHY WRITTEN THIS WAY - the second half is the important one
     * ProjectScopeInterceptor (ADR-021) has already checked that the caller may act on
     * project 7. It did NOT check that stakeholder 42 belongs to project 7, because it
     * only reads the URL. Without the comparison below, a manager of project 7 could
     * call DELETE /api/projects/7/parties-prenantes/42 and delete a contact of project
     * 9: the URL passes the scope check, and the id would be looked up on its own.
     *
     * WHY IT ANSWERS 404 AND NOT 403 ON A MISMATCH
     * The message is exactly the same in both cases, on purpose. A 403 would tell the
     * caller "this id exists, only not here", which is already information about
     * another project. A 404 says nothing.
     *
     * WHY PRIVATE
     * It is an internal helper, so it is not wrapped by the security proxy and carries
     * no @PreAuthorize. That is correct here: the two methods that call it, update()
     * and delete(), have already checked the permission before reaching this point.
     */
    private PartiePrenante loadPP(Long id, Long projectId) {
        // findActiveById returns Optional<PartiePrenante>. Optional is the standard Java
        // way to say "there may be no value here", so the empty case has to be handled on
        // the spot. orElseThrow turns it into the 404 straight away, instead of a null
        // that would explode a few lines later as a NullPointerException.
        PartiePrenante pp = ppRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Partie prenante introuvable : " + id));
        // The ownership check described above.
        // equals() and not == : both sides are Long objects, and == would compare object
        // references. Java caches only the small Long values, so == would look perfectly
        // correct in a test using id 5, and would start failing silently once the ids
        // pass 127 - the worst kind of bug, because it appears only in production.
        if (!pp.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Partie prenante introuvable : " + id);
        }
        return pp;
    }

    /**
     * Loads the project named in the URL, or throws NotFoundException (HTTP 404).
     *
     * WHY findActiveById AND NOT THE BUILT-IN findById
     * findActiveById adds "AND deleted = false". A soft-deleted project must behave
     * exactly like a project that never existed; without this, new stakeholders could
     * still be attached to a project that disappeared from every screen months ago.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}

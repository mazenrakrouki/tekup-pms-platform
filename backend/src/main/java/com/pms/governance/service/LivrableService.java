package com.pms.governance.service;

import com.pms.governance.dto.LivrableRequest;
import com.pms.governance.dto.LivrableResponse;
import com.pms.governance.entity.Livrable;
import com.pms.governance.entity.StatutLivrable;
import com.pms.governance.mapper.LivrableMapper;
import com.pms.governance.repository.LivrableRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * WHAT THIS FILE IS
 * -----------------
 * The business service of the deliverables ("livrables") of a project. A deliverable
 * is something the company owes the client: a specification document, a training
 * session, a released version. It carries a title, a description, a due date, and a
 * status that moves along a small lifecycle:
 *
 *     EN_ATTENTE  ->  EN_COURS  ->  LIVRE  ->  VALIDE
 *     (waiting)      (started)     (sent)     (accepted by the client)
 *
 * VALIDE is the end of the road and it is final: no method in this file can bring a
 * deliverable back from VALIDE, and both update() and delete() refuse to touch it.
 * One Livrable object = one row of the table "livrables", created by migration V11.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   Angular GovernanceService (core/services/governance.service.ts)
 *     -> HTTP /api/projects/{projectId}/livrables
 *        (GET list, POST create, PUT edit, DELETE, plus three PATCH buttons:
 *         /{id}/demarrer, /{id}/livrer, /{id}/valider)
 *     -> ProjectScopeInterceptor (ADR-021: may this caller act on THIS project?)
 *     -> LivrableController      (reads the URL, calls this file, returns the DTO)
 *     -> LivrableService         (THIS FILE: permission + transaction + state rules)
 *     -> LivrableRepository      (the only class that talks to the "livrables" table)
 *     -> LivrableMapper          (Livrable entity -> LivrableResponse, the JSON sent)
 * LivrableController is the only caller in the backend.
 *
 * WHY IT EXISTS - what would be missing if you deleted it
 * -------------------------------------------------------
 * It is the only place that holds the lifecycle rules. Without it:
 *   - a deliverable already accepted by the client (VALIDE) could be renamed, given a
 *     new due date, or deleted, and the acceptance record would no longer match what
 *     was actually accepted;
 *   - a deliverable could be marked VALIDE without ever being marked LIVRE, so the
 *     project would report an acceptance for something that was never sent;
 *   - the permission check and the "does this deliverable belong to this project?"
 *     check would both be gone.
 *
 * HOW IT RELATES TO THE THREE OTHER FILES IN THIS FOLDER
 * -----------------------------------------------------
 * RiskService, PartiePrenanteService and DemandeChangementService share the same
 * skeleton and the same two permissions; the governance screen shows the four of them
 * as four tabs of one project. This file and DemandeChangementService are the two that
 * carry a state machine, so they are the two that import BusinessRuleException.
 * The difference between the two: a change request is decided once (approved or
 * rejected) while a deliverable walks through four states.
 */

/**
 * WHAT IT DOES
 * Offers the deliverable operations of one project: list, create, edit, delete, and
 * the three status moves demarrer / livrer / valider. Every public method takes the
 * projectId read from the URL as its first argument and gives back a LivrableResponse
 * DTO (Data Transfer Object: a small object built only to travel to the browser).
 *
 * WHY THE THREE STATUS MOVES ARE SEPARATE METHODS, AND NOT A FIELD IN THE PUT
 * LivrableRequest deliberately has no "statut" field. If the status travelled in the
 * body of the PUT, any client could send {"statut":"VALIDE"} and jump the whole
 * lifecycle in one call. Here each move is its own method with its own rule about the
 * state it accepts, which is also why the API exposes them as three PATCH routes.
 *
 * WHY THE RULES ARE HERE AND NOT IN LivrableController
 * @PreAuthorize is applied by a Spring proxy placed around this bean, so on the
 * service the check protects EVERY caller of the method, not only the HTTP route.
 */
// @Service registers this class as a Spring bean: one shared instance is built at
// startup and injected into LivrableController.
// It is also what lets Spring wrap the bean in a proxy, and that proxy is what really
// applies @PreAuthorize and @Transactional below.
// Without it: the application fails to start with "no qualifying bean of type
// LivrableService", and a hand-made instance would run with no permission check and no
// transaction at all.
@Service
// Lombok writes, at compile time, a constructor taking the three `final` fields below,
// and Spring uses that single constructor to inject them.
// Why constructor injection rather than @Autowired on each field: the fields stay
// final, so nothing can swap the repository at runtime, and a unit test can build the
// service with mock objects in one line.
// Without it: only the default empty constructor exists, the three fields stay null,
// and the very first call ends in a NullPointerException.
@RequiredArgsConstructor
public class LivrableService {

    // The three collaborators, injected once at startup:
    //   livrableRepository - reads and writes the "livrables" table, soft-delete aware;
    //   projectRepository  - used only to prove that the project of the URL exists;
    //   livrableMapper     - MapStruct translator Livrable -> LivrableResponse.
    // `final` means each one is set by the constructor and can never be replaced, so no
    // later code can silently point this service at a different table.
    private final LivrableRepository livrableRepository;
    private final ProjectRepository  projectRepository;
    private final LivrableMapper     livrableMapper;

    /**
     * Returns every deliverable of one project that is not soft-deleted, ordered by due
     * date with the ones that have no date at the end.
     *
     * WHY WRITTEN THIS WAY
     * loadProject(projectId) is called for one reason only: the 404 it can throw. The
     * list query alone would return an empty list for a project id that does not exist,
     * and the screen would say "no deliverable yet" instead of "no such project".
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
    // the deliverables of a project he does not manage.
    // Without this line: any authenticated account could read what the company owes
    // this client and when it is due.
    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    // Runs the whole method inside one read-only database transaction.
    // Why: the entities stay attached while the mapper reads them, and Hibernate skips
    // the change tracking it would do in a writable transaction.
    // Without it: an accidental setter in the read path could be written to the
    // database at the end of the method, and each query could run on its own
    // connection, so one list could mix rows read at two different moments.
    @Transactional(readOnly = true)
    public List<LivrableResponse> findByProject(Long projectId) {
        // Called for its side effect only: it throws NotFoundException, which the global
        // handler turns into HTTP 404, when the project id of the URL does not exist or
        // was soft-deleted. The Project it returns is not needed here.
        loadProject(projectId);
        // findActiveByProjectId adds "AND deleted = false" and JOIN FETCHes the project in
        // the same SQL query, so the mapper can read project.getCode() without one extra
        // SELECT per deliverable. Without that fetch, 40 deliverables would mean 41
        // database round trips - the classic "N+1 queries" problem.
        return livrableMapper.toResponseList(livrableRepository.findActiveByProjectId(projectId));
    }

    /**
     * Adds one deliverable to a project and returns it with its new id.
     *
     * WHY WRITTEN THIS WAY
     * The project is loaded first and attached to the new row, so the foreign key
     * project_id can never point at a project that does not exist. The id comes from
     * the URL, and LivrableRequest has no projectId field at all: that is what stops a
     * caller from passing the ADR-021 scope check with a project he owns and then
     * creating the deliverable on somebody else's project.
     * The status is not set here on purpose - see the comment on the builder below.
     */
    // MANAGE_GOVERNANCE, not VIEW_GOVERNANCE: this method writes a row.
    // The two are separate rows of the permissions table. Following the authorization
    // matrix (UC-22 / UC-23), the project manager role holds both, while the director
    // holds VIEW_GOVERNANCE only.
    // Without this line: a director, who is only meant to watch the portfolio, could
    // add deliverables to every project he can see.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work: the SELECT on projects and the INSERT into livrables either both
    // happen or neither does, and other users see the new row only once the method
    // returns without an exception.
    // Without it, save() would open its own small transaction and commit at once, so a
    // failure later in the method would still leave the deliverable row behind.
    @Transactional
    public LivrableResponse create(Long projectId, LivrableRequest request) {
        Project project = loadProject(projectId);
        // Lombok's @Builder on the entity: every value is named, so adding a column later
        // cannot silently shift positional constructor arguments.
        // `statut` is NOT set here, and that is the whole point: the entity declares
        // @Builder.Default statut = EN_ATTENTE, so a new deliverable always starts at the
        // beginning of the lifecycle. If the status were read from the request, a client
        // could create a deliverable already VALIDE and skip the client acceptance step.
        // dateEcheance may legitimately be null (the column is nullable): a deliverable
        // is often written down before its date is agreed with the client.
        // The id, the dates and the deleted flag are absent on purpose: they come from
        // BaseEntity and are filled by the database and by JPA auditing.
        Livrable livrable = Livrable.builder()
                .project(project)
                .titre(request.titre())
                .description(request.description())
                .dateEcheance(request.dateEcheance())
                .build();
        // save() gives back the instance that now carries the id generated by Postgres
        // (BIGSERIAL). That returned instance is the one being mapped, so the browser
        // receives the id it needs for a later edit, delete, or status button. Mapping
        // the local `livrable` variable instead would send back id = null.
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Edits the title, the description and the due date of a deliverable that has not
     * been accepted yet, and returns the updated DTO.
     *
     * WHY WRITTEN THIS WAY
     * It reloads the row through loadLivrable() instead of trusting an object sent by
     * the client, and it checks the status BEFORE writing anything, so a refused call
     * leaves no half-modified row behind. Only three fields can change: the project,
     * the id, the status and the audit columns are never touched here, so a caller
     * cannot move a deliverable to another project or change its status with a PUT.
     */
    // Same write permission as create(): a deliverable is a commitment to the client.
    // Without this line: any holder of VIEW_GOVERNANCE could change the due date of a
    // deliverable and hide a delay.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work around a read-check-write sequence: loadLivrable() SELECTs the
    // row, the guard reads its status, and the setters produce the UPDATE.
    // Without it, the status could be read in one transaction and the row written in
    // another: a colleague validating the deliverable in between would see his
    // acceptance silently overwritten by this edit.
    @Transactional
    public LivrableResponse update(Long projectId, Long id, LivrableRequest request) {
        // loadLivrable does two things: it finds the non-deleted row, and it refuses the
        // call when that row belongs to another project. See the method at the bottom.
        Livrable livrable = loadLivrable(id, projectId);
        // The only frozen state. A deliverable accepted by the client must stay exactly as
        // it was accepted, otherwise the acceptance record means nothing.
        // Note the check is on VALIDE only: a deliverable already sent (LIVRE) can still
        // be corrected, because the client has not signed it off yet.
        // BusinessRuleException is turned into HTTP 422 Unprocessable Entity by
        // GlobalExceptionHandler, and the message is shown to the user as is. 422, not
        // 400: the body is well formed, it is the current state that forbids the action.
        // Without this guard: someone could rewrite the title of a deliverable the client
        // has already accepted, and the signed report would no longer match the system.
        if (livrable.getStatut() == StatutLivrable.VALIDE) {
            throw new BusinessRuleException("Impossible de modifier un livrable validé");
        }
        // The entity is managed by Hibernate inside the transaction, so these setters are
        // already enough on their own: the UPDATE statement is written at commit time.
        // The explicit save() below is therefore redundant but harmless; it is kept so
        // that "this method writes" stays visible when reading the code.
        // Note that a PUT replaces the three fields: a body sent without "description"
        // empties that column, it does not keep the previous text.
        livrable.setTitre(request.titre());
        livrable.setDescription(request.description());
        livrable.setDateEcheance(request.dateEcheance());
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Moves one deliverable from EN_ATTENTE to EN_COURS ("work has started") and returns
     * the updated DTO. Reached by PATCH /api/projects/{projectId}/livrables/{id}/demarrer.
     *
     * WHY A DEDICATED METHOD RATHER THAN A STATUS FIELD IN THE PUT
     * Each move has its own rule about the state it accepts, and the rule must be
     * checked server-side. Here the rule is strict: exactly one source state. Starting
     * a deliverable that is already LIVRE would make no sense, and starting one that is
     * already EN_COURS would silently reset nothing while telling the user it worked.
     */
    // MANAGE_GOVERNANCE: changing the status of a deliverable is a write.
    // Without this line: a read-only account could declare work started on a project he
    // only watches.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work around read-check-write. Without it, two users clicking the
    // button at the same time could both read EN_ATTENTE and both write EN_COURS in two
    // separate transactions, and the audit columns would show only the last one.
    @Transactional
    public LivrableResponse demarrer(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        // != EN_ATTENTE : only a deliverable that has not started can be started. This is
        // the strictest of the three moves, and it is the opposite style of livrer()
        // below, which only forbids one state instead of demanding one.
        // BusinessRuleException becomes HTTP 422, with this message shown to the user.
        // Without this guard: clicking "start" on an accepted deliverable would push it
        // back to EN_COURS and reopen something the client already signed off.
        if (livrable.getStatut() != StatutLivrable.EN_ATTENTE) {
            throw new BusinessRuleException("Seul un livrable en attente peut être démarré");
        }
        // The new status is written by the server, never taken from the request body, so
        // the lifecycle can only be walked one legal step at a time.
        livrable.setStatut(StatutLivrable.EN_COURS);
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Marks one deliverable as LIVRE ("sent to the client") and returns the updated DTO.
     * Reached by PATCH /api/projects/{projectId}/livrables/{id}/livrer.
     *
     * WHY WRITTEN THIS WAY - read the guard carefully
     * This move is written as a forbidden state (VALIDE) instead of a required state.
     * The practical effect is that a deliverable can go from EN_ATTENTE straight to
     * LIVRE without ever being EN_COURS, and that calling it twice on a LIVRE row is
     * accepted and changes nothing. It is looser than demarrer() and valider(), which
     * each accept exactly one source state.
     */
    // MANAGE_GOVERNANCE: declaring a delivery to the client is a write, and it is the
    // step that opens the door to valider().
    // Without this line: a read-only account could declare a delivery that never
    // happened.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work around read-check-write, for the same reason as the other two
    // status moves: the status must be read and written inside a single transaction.
    @Transactional
    public LivrableResponse livrer(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        // == VALIDE : the only state that refuses the move. Once the client has accepted
        // the deliverable, re-declaring a delivery would contradict the acceptance.
        // BusinessRuleException becomes HTTP 422, with this message shown to the user.
        // Without this guard: an accepted deliverable would fall back to LIVRE, and the
        // project would look as if the client had never signed it off.
        if (livrable.getStatut() == StatutLivrable.VALIDE) {
            throw new BusinessRuleException("Un livrable déjà validé ne peut pas être modifié");
        }
        // The new status is written by the server, never taken from the request body.
        livrable.setStatut(StatutLivrable.LIVRE);
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Marks one deliverable as VALIDE ("accepted by the client") and returns the updated
     * DTO. Reached by PATCH /api/projects/{projectId}/livrables/{id}/valider.
     *
     * WHY WRITTEN THIS WAY
     * This is the one move that must never be reachable by accident, because VALIDE
     * freezes the row for good: after it, update() and delete() both refuse, and no
     * method in this file can bring the deliverable back. So the rule demands exactly
     * one source state, LIVRE: you cannot accept something that was never sent.
     */
    // MANAGE_GOVERNANCE: this records the client acceptance and freezes the row.
    // Without this line: a read-only account could freeze a deliverable that is still
    // being worked on, and nobody could edit or delete it afterwards.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work around read-check-write. Without it, a colleague could edit the
    // title between the status read and the status write, and that edit would land on a
    // row that is already frozen by the time it commits.
    @Transactional
    public LivrableResponse valider(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        // != LIVRE : acceptance is only possible on something that has been sent. This is
        // what makes the pair livrer() / valider() a real two-step control, one for the
        // company and one for the client.
        // BusinessRuleException becomes HTTP 422, with this message shown to the user.
        // Without this guard: a deliverable could be accepted straight from EN_ATTENTE,
        // so the system would report a client acceptance for work never delivered.
        if (livrable.getStatut() != StatutLivrable.LIVRE) {
            throw new BusinessRuleException("Seul un livrable livré peut être validé");
        }
        // The terminal state. Written by the server only, and there is no method in this
        // file that leaves it: reopening an accepted deliverable is a decision that has
        // to be taken outside the application, on purpose.
        livrable.setStatut(StatutLivrable.VALIDE);
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Soft-deletes one deliverable that has not been accepted: the row stays in the
     * table with deleted = true. Returns nothing, and the controller answers 204.
     *
     * WHY A FLAG AND NOT A REAL SQL DELETE
     * A deliverable is a commitment to the client. The audit trail must still show that
     * it existed and who removed it, which the created_by / updated_by / updated_at
     * columns of BaseEntity keep (columns added by migration V19). Every read in this
     * package goes through a findActive...() query that adds "AND deleted = false", so a
     * flagged row disappears from every screen while staying available for an audit.
     * With a real DELETE, a wrong click would be final and nothing could be restored.
     */
    // MANAGE_GOVERNANCE: removing a deliverable removes a commitment from the project.
    // Without this line: a read-only account could make the deliverable list of a
    // project empty.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // The read, the check and the flag update are one unit of work.
    // Without it, the status could be read, the deliverable validated by somebody else,
    // and the delete still committed afterwards on a row that had become frozen.
    @Transactional
    public void delete(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        // Same frozen state as update(): what the client accepted cannot be made to
        // disappear from the list.
        // BusinessRuleException becomes HTTP 422, with this message shown to the user.
        // Without this guard: an accepted deliverable could be hidden from every screen,
        // and the project would look lighter than what was really contracted.
        if (livrable.getStatut() == StatutLivrable.VALIDE) {
            throw new BusinessRuleException("Impossible de supprimer un livrable validé");
        }
        // The deleted flag lives on BaseEntity, the parent class shared by every entity of
        // the project, so the same soft-delete rule applies in the whole application.
        livrable.setDeleted(true);
        livrableRepository.save(livrable);
    }

    /**
     * Loads one non-deleted deliverable AND proves it belongs to the project of the URL.
     * Gives back the managed entity, or throws NotFoundException (HTTP 404).
     * It is the first line of the five methods that work on one deliverable: update(),
     * demarrer(), livrer(), valider() and delete(). findByProject() does not use it,
     * because it works on the whole list and calls loadProject() instead.
     *
     * WHY WRITTEN THIS WAY - the second half is the important one
     * ProjectScopeInterceptor (ADR-021) has already checked that the caller may act on
     * project 7. It did NOT check that deliverable 42 belongs to project 7, because it
     * only reads the URL. Without the comparison below, a manager of project 7 could
     * call PATCH /api/projects/7/livrables/42/valider and accept a deliverable of
     * project 9: the URL passes the scope check, and the id would be looked up alone.
     *
     * WHY IT ANSWERS 404 AND NOT 403 ON A MISMATCH
     * The message is exactly the same in both cases, on purpose. A 403 would tell the
     * caller "this id exists, only not here", which is already information about
     * another project. A 404 says nothing.
     *
     * WHY PRIVATE
     * It is an internal helper, so it is not wrapped by the security proxy and carries
     * no @PreAuthorize. That is correct here: the five methods that call it have
     * already checked the permission before reaching this point.
     */
    private Livrable loadLivrable(Long id, Long projectId) {
        // findActiveById returns Optional<Livrable>. Optional is the standard Java way to
        // say "there may be no value here", so the empty case has to be handled on the
        // spot. orElseThrow turns it into the 404 straight away, instead of a null that
        // would explode a few lines later as a NullPointerException.
        Livrable livrable = livrableRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Livrable introuvable : " + id));
        // The ownership check described above.
        // equals() and not == : both sides are Long objects, and == would compare object
        // references. Java caches only the small Long values, so == would look perfectly
        // correct in a test using id 5, and would start failing silently once the ids
        // pass 127 - the worst kind of bug, because it appears only in production.
        if (!livrable.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Livrable introuvable : " + id);
        }
        return livrable;
    }

    /**
     * Loads the project named in the URL, or throws NotFoundException (HTTP 404).
     *
     * WHY findActiveById AND NOT THE BUILT-IN findById
     * findActiveById adds "AND deleted = false". A soft-deleted project must behave
     * exactly like a project that never existed; without this, new deliverables could
     * still be attached to a project that disappeared from every screen months ago.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}

package com.pms.governance.service;

import com.pms.governance.dto.DemandeChangementRequest;
import com.pms.governance.dto.DemandeChangementResponse;
import com.pms.governance.entity.DemandeChangement;
import com.pms.governance.entity.StatutChangement;
import com.pms.governance.mapper.DemandeChangementMapper;
import com.pms.governance.repository.DemandeChangementRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/*
 * WHAT THIS FILE IS
 * -----------------
 * The business service of the change requests ("demandes de changement") of a project.
 * A change request is a written demand to modify something that was already agreed:
 * somebody files it, and the project manager later approves it or rejects it. It
 * carries the author (demandeur), a title, a description, a priority, the date it was
 * filed, a status and the date of the decision.
 *
 * The status is a very short lifecycle, and it is decided only once:
 *
 *                   /-> APPROUVE   (approved, dateDecision filled)
 *     EN_ATTENTE  --
 *     (waiting)      \-> REJETE     (rejected, dateDecision filled)
 *
 * Both end states are final: once the decision is taken, this file refuses to edit or
 * delete the request. One DemandeChangement object = one row of the table
 * "demandes_changement", created by migration V11.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   Angular GovernanceService (core/services/governance.service.ts)
 *     -> HTTP /api/projects/{projectId}/demandes-changement
 *        (GET list, POST create, PUT edit, DELETE, plus two PATCH decision buttons:
 *         /{id}/approuver and /{id}/rejeter)
 *     -> ProjectScopeInterceptor        (ADR-021: may this caller act on THIS project?)
 *     -> DemandeChangementController    (reads the URL, calls this file)
 *     -> DemandeChangementService       (THIS FILE: permission + transaction + rules)
 *     -> DemandeChangementRepository    (talks to the "demandes_changement" table)
 *        and UserRepository             (turns demandeurId into a real User)
 *     -> DemandeChangementMapper        (entity -> DemandeChangementResponse JSON)
 * DemandeChangementController is the only caller in the backend.
 *
 * WHY IT EXISTS - what would be missing if you deleted it
 * -------------------------------------------------------
 * It is the only place that makes the approval step mean something:
 *   - the status and the decision date are written by the server alone, never taken
 *     from the request body, so nobody can approve his own change request with a PUT;
 *   - a request that has been decided can no longer be edited or deleted, so the
 *     approved text stays exactly the text that was approved;
 *   - the author is resolved through UserRepository, so a request can never be filed
 *     in the name of an account that does not exist or was deactivated.
 * Without this class, the change log of the project would be rewritable after the fact
 * and would prove nothing.
 *
 * HOW IT RELATES TO THE THREE OTHER FILES IN THIS FOLDER
 * -----------------------------------------------------
 * RiskService, LivrableService and PartiePrenanteService share the same skeleton and
 * the same two permissions; the governance screen shows the four of them as four tabs
 * of one project. This file is the only one of the four that needs a fourth
 * collaborator, UserRepository, because it is the only one that points at a person.
 * With LivrableService it is one of the two that carry a state machine, which is why
 * both import BusinessRuleException.
 */

/**
 * WHAT IT DOES
 * Offers the change-request operations of one project: list, create, edit, delete, and
 * the two decisions approuver / rejeter. Every public method takes the projectId read
 * from the URL as its first argument and gives back a DemandeChangementResponse DTO
 * (Data Transfer Object: a small object built only to travel to the browser).
 *
 * WHY THE DECISIONS ARE SEPARATE METHODS AND NOT A STATUS FIELD IN THE PUT
 * DemandeChangementRequest deliberately has no "statut" and no "dateDecision" field.
 * If the status travelled in the body, the person who filed the request could send
 * {"statut":"APPROUVE"} in a plain PUT and approve himself, and the whole governance
 * step would be decoration. Here the two decisions are their own methods, each
 * refusing a request that has already been decided, and each stamping the date from
 * the server clock.
 *
 * WHY THE RULES ARE HERE AND NOT IN THE CONTROLLER
 * @PreAuthorize is applied by a Spring proxy placed around this bean, so on the
 * service the check protects EVERY caller of the method, not only the HTTP route.
 */
// @Service registers this class as a Spring bean: one shared instance is built at
// startup and injected into DemandeChangementController.
// It is also what lets Spring wrap the bean in a proxy, and that proxy is what really
// applies @PreAuthorize and @Transactional below.
// Without it: the application fails to start with "no qualifying bean of type
// DemandeChangementService", and a hand-made instance would run with no permission
// check and no transaction at all.
@Service
// Lombok writes, at compile time, a constructor taking the four `final` fields below,
// and Spring uses that single constructor to inject them.
// Why constructor injection rather than @Autowired on each field: the fields stay
// final, so nothing can swap a repository at runtime, and a unit test can build the
// service with mock objects in one line.
// Without it: only the default empty constructor exists, the four fields stay null,
// and the very first call ends in a NullPointerException.
@RequiredArgsConstructor
public class DemandeChangementService {

    // The four collaborators, injected once at startup:
    //   dcRepository      - reads and writes "demandes_changement", soft-delete aware;
    //   projectRepository - used only to prove that the project of the URL exists;
    //   userRepository    - turns the demandeurId of the body into a real User;
    //   dcMapper          - MapStruct translator DemandeChangement -> ...Response.
    // userRepository is what makes this service different from the three others in the
    // folder: a change request always points at a person, and that person has to exist.
    // `final` means each one is set by the constructor and can never be replaced, so no
    // later code can silently point this service at a different table.
    private final DemandeChangementRepository dcRepository;
    private final ProjectRepository           projectRepository;
    private final UserRepository              userRepository;
    private final DemandeChangementMapper     dcMapper;

    /**
     * Returns every change request of one project that is not soft-deleted, most
     * recently filed first.
     *
     * WHY WRITTEN THIS WAY
     * loadProject(projectId) is called for one reason only: the 404 it can throw. The
     * list query alone would return an empty list for a project id that does not exist,
     * and the screen would say "no change request yet" instead of "no such project".
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
    // the change log of a project he does not manage.
    // Without this line: any authenticated account could read every change asked for on
    // the project, including the ones that were rejected.
    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    // Runs the whole method inside one read-only database transaction.
    // Why: the entities stay attached while the mapper reads them, and Hibernate skips
    // the change tracking it would do in a writable transaction.
    // Without it: an accidental setter in the read path could be written to the
    // database at the end of the method, and each query could run on its own
    // connection, so one list could mix rows read at two different moments.
    @Transactional(readOnly = true)
    public List<DemandeChangementResponse> findByProject(Long projectId) {
        // Called for its side effect only: it throws NotFoundException, which the global
        // handler turns into HTTP 404, when the project id of the URL does not exist or
        // was soft-deleted. The Project it returns is not needed here.
        loadProject(projectId);
        // findActiveByProjectId adds "AND deleted = false" and JOIN FETCHes BOTH the
        // project and the author in the same SQL query, so the mapper can read
        // project.getCode() and demandeur.getFullName() without extra SELECTs. Without
        // that double fetch, 20 requests would mean 41 database round trips - the
        // classic "N+1 queries" problem, doubled here because there are two relations.
        return dcMapper.toResponseList(dcRepository.findActiveByProjectId(projectId));
    }

    /**
     * Files one change request on a project and returns it with its new id.
     *
     * WHY WRITTEN THIS WAY
     * The project and the author are both loaded and attached before anything is built,
     * so neither foreign key can point at a row that does not exist. The project id
     * comes from the URL, and DemandeChangementRequest has no projectId field: that is
     * what stops a caller from passing the ADR-021 scope check with a project he owns
     * and then filing the request against somebody else's project.
     * The status and the decision date are not set here - see the builder comment.
     *
     * Note there is no check that the author is a member of the project team: any
     * active account can be named as the requester, which is what allows a request to
     * be filed on behalf of a client contact who has an account.
     */
    // MANAGE_GOVERNANCE, not VIEW_GOVERNANCE: this method writes a row.
    // The two are separate rows of the permissions table. Following the authorization
    // matrix (UC-22 / UC-23), the project manager role holds both, while the director
    // holds VIEW_GOVERNANCE only.
    // Without this line: a director, who is only meant to watch the portfolio, could
    // file change requests on every project he can see.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work: the two SELECTs (project, user) and the INSERT either all happen
    // or none does, and other users see the new row only once the method returns without
    // an exception.
    // Without it, each repository call would run in its own small transaction, so a
    // failure after the INSERT would still leave the change request row behind.
    @Transactional
    public DemandeChangementResponse create(Long projectId, DemandeChangementRequest request) {
        // Both lookups happen BEFORE anything is built. Validating first and writing after
        // means a refused request (bad project id, unknown author) leaves no half-created
        // row behind.
        Project project = loadProject(projectId);
        User demandeur = loadUser(request.demandeurId());
        // Lombok's @Builder on the entity: every value is named, so adding a column later
        // cannot silently shift positional constructor arguments.
        // `statut` and `dateDecision` are NOT set here, and that is the whole point: the
        // entity declares @Builder.Default statut = EN_ATTENTE and leaves dateDecision
        // null, so a new request always starts waiting for a decision. If either value
        // could be sent by the client, the author could file a request already APPROUVE.
        // `priorite` is always provided, because it is @NotNull in the request; the
        // entity default (NORMALE) therefore never applies on this path. It is an enum,
        // not free text, so a wrong value such as "URGENT" is refused by Jackson with a
        // 400 instead of hitting the check constraint chk_dc_priorite of migration V11.
        // The id, the audit dates and the deleted flag come from BaseEntity.
        DemandeChangement dc = DemandeChangement.builder()
                .project(project)
                .demandeur(demandeur)
                .titre(request.titre())
                .description(request.description())
                .priorite(request.priorite())
                .dateDemande(request.dateDemande())
                .build();
        // save() gives back the instance that now carries the id generated by Postgres
        // (BIGSERIAL). That returned instance is the one being mapped, so the browser
        // receives the id it needs for a later edit, delete, or decision button.
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    /**
     * Edits a change request that has not been decided yet, and returns the updated DTO.
     *
     * WHY WRITTEN THIS WAY
     * It reloads the row through loadDC() instead of trusting an object sent by the
     * client, and it checks the status BEFORE looking the author up or writing
     * anything, so a refused call leaves no half-modified row behind. Five fields can
     * change, including the author; the project, the id, the status, the decision date
     * and the audit columns are never touched, so a PUT can neither move the request to
     * another project nor decide it.
     */
    // Same write permission as create(): editing a change request changes the official
    // record of what was asked for.
    // Without this line: any holder of VIEW_GOVERNANCE could rewrite the text of a
    // pending request just before the manager reads it and decides.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work around a read-check-write sequence: loadDC() SELECTs the row, the
    // guard reads its status, loadUser() SELECTs the author and the setters produce the
    // UPDATE.
    // Without it, the status could be read in one transaction and the row written in
    // another: a manager approving the request in between would see the approved text
    // silently replaced by this edit.
    @Transactional
    public DemandeChangementResponse update(Long projectId, Long id, DemandeChangementRequest request) {
        // loadDC does two things: it finds the non-deleted row, and it refuses the call
        // when that row belongs to another project. See the method at the bottom.
        DemandeChangement dc = loadDC(id, projectId);
        // != EN_ATTENTE : once approved or rejected, the request is frozen. The text that
        // was decided must stay the text that was decided.
        // BusinessRuleException is turned into HTTP 422 Unprocessable Entity by
        // GlobalExceptionHandler, and the message is shown to the user as is. 422, not
        // 400: the body is well formed, it is the current state that forbids the action.
        // Without this guard: somebody could get a small change approved, then rewrite
        // the request into a much bigger one while keeping the approval stamp.
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("Impossible de modifier une demande déjà traitée");
        }
        // The author can be corrected, so the id is resolved again. loadUser() answers 404
        // when the account does not exist or was soft-deleted, which is what keeps the
        // foreign key demandeur_id pointing at a usable account.
        User demandeur = loadUser(request.demandeurId());
        // The entity is managed by Hibernate inside the transaction, so these setters are
        // already enough on their own: the UPDATE statement is written at commit time.
        // The explicit save() below is therefore redundant but harmless; it is kept so
        // that "this method writes" stays visible when reading the code.
        // Note that a PUT replaces all five fields: a body sent without "description"
        // empties that column, it does not keep the previous text.
        dc.setDemandeur(demandeur);
        dc.setTitre(request.titre());
        dc.setDescription(request.description());
        dc.setPriorite(request.priorite());
        dc.setDateDemande(request.dateDemande());
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    /**
     * Approves one pending change request and returns the updated DTO. Reached by
     * PATCH /api/projects/{projectId}/demandes-changement/{id}/approuver.
     *
     * WHY A DEDICATED METHOD RATHER THAN A STATUS FIELD IN THE PUT
     * The decision is the whole point of the feature, so it must be a server-side
     * operation with its own rule and its own permission check. The method writes both
     * the status and the decision date; neither value can be sent by the client.
     * It is the exact mirror of rejeter() below: same guard, same date stamp, only the
     * final status differs. They are kept as two methods rather than one method with a
     * boolean, because two URLs are easier to read in an access log and in the audit.
     */
    // MANAGE_GOVERNANCE: approving is the strongest write of this file, because it is
    // what makes the change official and freezes the row.
    // Without this line: a read-only account, or the author himself if he only had
    // VIEW_GOVERNANCE, could approve the change he asked for.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work around read-check-write. Two writes happen below (status and
    // decision date); without the transaction a crash between them could leave a request
    // marked APPROUVE with no decision date, and the change log could not say when it
    // was decided.
    @Transactional
    public DemandeChangementResponse approuver(Long projectId, Long id) {
        DemandeChangement dc = loadDC(id, projectId);
        // != EN_ATTENTE : a request is decided exactly once. This also makes the button
        // safe against a double click, because the second call is refused instead of
        // overwriting the first decision and its date.
        // BusinessRuleException becomes HTTP 422, with this message shown to the user.
        // Without this guard: a request rejected last month could be flipped to APPROUVE
        // today, and the original decision would disappear without any trace.
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("La demande a déjà été traitée");
        }
        // The status and the date are written by the server, never read from the body.
        // LocalDate.now() is the server clock and a calendar day with no time zone, so
        // the decision date cannot be back-dated by the client and cannot shift by one
        // day depending on where the browser is. That date is the proof of when the
        // change entered the contract.
        dc.setStatut(StatutChangement.APPROUVE);
        dc.setDateDecision(LocalDate.now());
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    /**
     * Rejects one pending change request and returns the updated DTO. Reached by
     * PATCH /api/projects/{projectId}/demandes-changement/{id}/rejeter.
     *
     * WHY IT LOOKS EXACTLY LIKE approuver()
     * Both are decisions, so both must follow the same rule: only a pending request can
     * be decided, and the decision date is stamped by the server. Only the final status
     * differs. A rejection is recorded, not deleted: the project must be able to show
     * later that the change was asked for and refused, and on which day.
     */
    // MANAGE_GOVERNANCE: a rejection is a decision on the contract, so it is a write
    // reserved to the project manager, not to any reader of the governance screen.
    // Without this line: a read-only account could close a change request the client is
    // still waiting for.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // One unit of work around read-check-write, for the same reason as approuver(): the
    // status and the decision date must be written together or not at all.
    @Transactional
    public DemandeChangementResponse rejeter(Long projectId, Long id) {
        DemandeChangement dc = loadDC(id, projectId);
        // != EN_ATTENTE : same rule as approuver(). A request already decided cannot be
        // decided again, in either direction.
        // BusinessRuleException becomes HTTP 422, with this message shown to the user.
        // Without this guard: an approved change could be quietly turned into a
        // rejection after the work had already started.
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("La demande a déjà été traitée");
        }
        // Written by the server only, exactly like in approuver(). REJETE is final: no
        // method in this file brings a request back to EN_ATTENTE, so re-opening a
        // refused change means filing a new request, which keeps the history readable.
        dc.setStatut(StatutChangement.REJETE);
        dc.setDateDecision(LocalDate.now());
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    /**
     * Soft-deletes one change request that has not been decided: the row stays in the
     * table with deleted = true. Returns nothing, and the controller answers 204.
     *
     * WHY A FLAG AND NOT A REAL SQL DELETE
     * The change log is a governance record. The audit trail must still show that the
     * request existed and who removed it, which the created_by / updated_by /
     * updated_at columns of BaseEntity keep (columns added by migration V19). Every
     * read in this package goes through a findActive...() query that adds "AND deleted
     * = false", so a flagged row disappears from every screen while staying available
     * for an audit.
     *
     * WHY THE STATUS IS CHECKED TOO
     * Without the guard below, deleting would become the way around the freeze: a
     * manager could not edit a decided request, but he could make it disappear, which
     * has the same effect on what the client sees.
     */
    // MANAGE_GOVERNANCE: removing a change request removes a demand from the record.
    // Without this line: a read-only account could empty the change log of a project.
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    // The read, the check and the flag update are one unit of work.
    // Without it, the status could be read as EN_ATTENTE, the request approved by
    // somebody else, and the delete still committed afterwards on a row that had become
    // frozen in the meantime.
    @Transactional
    public void delete(Long projectId, Long id) {
        DemandeChangement dc = loadDC(id, projectId);
        // != EN_ATTENTE : only a request still waiting can be withdrawn. This is the
        // strictest of the three guards of this file, because it also forbids deleting a
        // REJETE request: a refusal is part of the history and has to stay visible.
        // BusinessRuleException becomes HTTP 422, with this message shown to the user.
        // Without this guard: an approved change could be hidden from every screen, and
        // the extra work it authorised would appear in the project with no reason.
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("Impossible de supprimer une demande déjà traitée");
        }
        // The deleted flag lives on BaseEntity, the parent class shared by every entity of
        // the project, so the same soft-delete rule applies in the whole application.
        dc.setDeleted(true);
        dcRepository.save(dc);
    }

    /**
     * Loads one non-deleted change request AND proves it belongs to the project of the
     * URL. Gives back the managed entity, or throws NotFoundException (HTTP 404).
     * It is the first line of the four methods that work on one existing request:
     * update(), approuver(), rejeter() and delete(). create() does not use it, because
     * there is no row yet, and findByProject() works on the whole list.
     *
     * WHY WRITTEN THIS WAY - the second half is the important one
     * ProjectScopeInterceptor (ADR-021) has already checked that the caller may act on
     * project 7. It did NOT check that request 42 belongs to project 7, because it only
     * reads the URL. Without the comparison below, a manager of project 7 could call
     * PATCH /api/projects/7/demandes-changement/42/approuver and approve a change on
     * project 9: the URL passes the scope check, and the id would be looked up alone.
     *
     * WHY IT ANSWERS 404 AND NOT 403 ON A MISMATCH
     * The message is exactly the same in both cases, on purpose. A 403 would tell the
     * caller "this id exists, only not here", which is already information about
     * another project. A 404 says nothing.
     *
     * WHY PRIVATE
     * It is an internal helper, so it is not wrapped by the security proxy and carries
     * no @PreAuthorize. That is correct here: the four methods that call it have
     * already checked the permission before reaching this point.
     */
    private DemandeChangement loadDC(Long id, Long projectId) {
        // findActiveById returns Optional<DemandeChangement>. Optional is the standard Java
        // way to say "there may be no value here", so the empty case has to be handled on
        // the spot. orElseThrow turns it into the 404 straight away, instead of a null
        // that would explode a few lines later as a NullPointerException.
        DemandeChangement dc = dcRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Demande de changement introuvable : " + id));
        // The ownership check described above.
        // equals() and not == : both sides are Long objects, and == would compare object
        // references. Java caches only the small Long values, so == would look perfectly
        // correct in a test using id 5, and would start failing silently once the ids
        // pass 127 - the worst kind of bug, because it appears only in production.
        if (!dc.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Demande de changement introuvable : " + id);
        }
        return dc;
    }

    /**
     * Loads the project named in the URL, or throws NotFoundException (HTTP 404).
     *
     * WHY findActiveById AND NOT THE BUILT-IN findById
     * findActiveById adds "AND deleted = false". A soft-deleted project must behave
     * exactly like a project that never existed; without this, new change requests
     * could still be filed on a project that disappeared from every screen months ago.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /**
     * Turns the demandeurId sent in the body into a real, active User, or throws
     * NotFoundException (HTTP 404).
     *
     * WHY THE CLIENT SENDS AN ID AND NOT A NAME
     * A name typed by the client could be anybody. Resolving the id against the users
     * table means the change request is always attached to a real account, so the
     * foreign key fk_dc_user of migration V11 can never be violated and the governance
     * screen can always display a full name for the author.
     *
     * WHY IT IS WRITTEN findById(...).filter(...)
     * The soft-delete rule is applied here in Java, with the filter below, instead of
     * being part of the SQL query. The result is the same: a deactivated account
     * behaves like an account that never existed. Note that UserRepository does offer a
     * findActiveById query, so this helper could be rewritten to use it; the current
     * form was kept because the two do the same job here.
     */
    private User loadUser(Long id) {
        // .filter(u -> !u.isDeleted()) : Optional.filter drops the value when the test is
        // false, so a soft-deleted user turns the Optional empty and orElseThrow answers
        // 404, exactly as for an unknown id. The lambda `u -> !u.isDeleted()` is just a
        // short way of writing that test.
        // Why it matters: without the filter, a change request could be filed in the
        // name of an employee who left the company, and the governance screen would show
        // that person as the author of a change nobody can ask about.
        // Note this returns the user found by id, whatever project he belongs to; being
        // the author of a change request does not require being on the project team.
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}

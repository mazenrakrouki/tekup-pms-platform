package com.pms.mission.service;

import com.pms.mission.dto.ComposanteRequest;
import com.pms.mission.dto.ComposanteResponse;
import com.pms.mission.entity.ComposanteMission;
import com.pms.mission.entity.Mission;
import com.pms.mission.mapper.ComposanteMapper;
import com.pms.mission.repository.ComposanteMissionRepository;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * WHAT THIS FILE IS
 * The business rules for the cost lines of a mission (a work trip). In the business language
 * of the company a "composante" is one cost item of a trip: PERDIEM (the daily allowance),
 * BILLET (plane or train ticket), TIMBRE (stamp or official fee), TRANSPORT, SEJOUR (the stay
 * itself). Each line carries one amount and one currency. The trip itself (purpose, place,
 * dates) belongs to {@link MissionService}.
 *
 * WHERE IT SITS IN THE FLOW
 * MissionController (URLs under /api/projects/{projectId}/missions/{missionId}/composantes)
 *   -> ComposanteService (this file)
 *   -> MissionService.loadMission(...) for the parent trip,
 *      and ComposanteMissionRepository for the cost rows themselves
 *   -> ComposanteMapper, which turns a ComposanteMission entity into the ComposanteResponse
 *      record (a DTO, that is a small flat object made only to be sent back as JSON).
 *
 * WHY IT EXISTS
 * Two reasons.
 *  1. It keeps the money rules of a trip out of MissionService, which is already responsible
 *     for the trip itself. Each class then has one job and one reason to change.
 *  2. It re-checks the whole chain project -> mission -> composante on every single call. The
 *     scope interceptor (ADR-021) only reads the {projectId} written in the URL; it cannot know
 *     which project mission 77 really belongs to, and it knows nothing about cost line 512. If
 *     this class disappeared and the controller called the repository directly, a user allowed
 *     on project 12 could ask for /api/projects/12/missions/77/composantes and read the trip
 *     costs of project 40.
 *
 * WHY THE SAME TWO CHECKS COME BACK IN EVERY METHOD
 * They are written again in each method on purpose, because each method is a separate entry
 * point reached directly from an HTTP request. A check written once in a shared private helper
 * would still be fine, but a check written only in findByMission() would leave create(),
 * update() and delete() open. The rule here is: every public method proves the parent chain by
 * itself before it touches anything.
 *
 * WHY 404 AND NOT 403 WHEN THE CHAIN IS WRONG
 * Answering 403 "forbidden" would tell the caller that mission 77 or cost line 512 does exist
 * somewhere else in the application. NotFoundException gives 404 and reveals nothing.
 */
@Service
@RequiredArgsConstructor
public class ComposanteService {

    // Lombok's @RequiredArgsConstructor (on the class above) writes the constructor for these
    // three final fields and Spring passes the real beans into it at start-up.
    // Note that this service depends on MissionService, not on MissionRepository: loading a
    // mission must stay one single piece of code, with one "not deleted" rule and one error
    // message. Going straight to the repository would create a second version of that rule,
    // and the day the rule changes, only one of the two would be updated.
    private final ComposanteMissionRepository composanteRepository;
    private final MissionService              missionService;
    private final ComposanteMapper            composanteMapper;

    /**
     * Lists the cost lines of one mission that are not deleted, ordered by cost type.
     *
     * What it gives back: a list of ComposanteResponse records, ready to be sent as JSON.
     * An empty list is a normal answer: a trip can be registered before its costs are known.
     */
    // Reading the costs of a trip needs VIEW_MISSION. The check sits here on the service and not
    // on the controller, so that any future caller (a report, a batch, another service) is
    // protected too, and so that giving this capability to a new role tomorrow is one row in
    // role_permissions and zero line of code (ADR-001).
    @PreAuthorize("hasAuthority('VIEW_MISSION')")
    // Read-only transaction: one stable picture of the data for the whole method, and Hibernate
    // is told it will not have to write anything, so it does not keep a copy of each loaded row
    // to compare at the end. Without readOnly, a stray setter on the loaded Mission would be
    // written to the database by a method that is only supposed to read.
    @Transactional(readOnly = true)
    public List<ComposanteResponse> findByMission(Long projectId, Long missionId) {
        Mission mission = missionService.loadMission(missionId);
        // The mission must really belong to the project named in the URL. Without this line, a
        // user allowed on project 12 could read the costs of a mission of project 40 just by
        // putting that mission id in the path.
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }
        return composanteMapper.toResponseList(composanteRepository.findActiveByMissionId(missionId));
    }

    /**
     * Adds one cost line to a mission and gives back the saved row.
     *
     * Why the parent mission is loaded even though only its id is stored: the load proves the
     * trip exists, is not deleted, and belongs to the project of the URL. Saving a cost line
     * with a Mission built from a bare id would pass the foreign key but would happily attach
     * money to a trip that was deleted, or to a trip of another project.
     */
    // Writing costs is reserved to MANAGE_MISSION, which the matrix grants to the project
    // manager. A developer holds VIEW_MISSION only: he can read the costs of the trip but
    // cannot declare an extra 500 TND of per diem for himself.
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    // One unit of work: the read of the mission and the insert of the cost line either both
    // succeed or neither is kept, and the row becomes visible to other users only at commit.
    @Transactional
    public ComposanteResponse create(Long projectId, Long missionId, ComposanteRequest request) {
        Mission mission = missionService.loadMission(missionId);
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }

        // The builder names each value it sets, so two fields of the same type cannot be swapped
        // by mistake. No id is set: the database gives it, and BaseEntity fills created_at and
        // created_by through the JPA auditing listener.
        ComposanteMission composante = ComposanteMission.builder()
                .mission(mission)
                .typeComposante(request.typeComposante())
                .montant(request.montant())
                // The currency code is put in upper case before it is stored, so "tnd", "Tnd"
                // and "TND" all become one single value. Without it, a total grouped by currency
                // would show two separate lines, "tnd" and "TND", for the same money.
                .devise(request.devise().toUpperCase())
                .description(request.description())
                .build();

        // save() gives back the instance carrying the generated id, which the response must
        // contain so the screen knows which row it has just created.
        return composanteMapper.toResponse(composanteRepository.save(composante));
    }

    /**
     * Changes one cost line of one mission and gives back its new state.
     *
     * Why both the mission AND the cost line are checked: the URL carries three ids and each one
     * has to be proved against the next. Checking only that the cost line exists would let a
     * manager of project 12 send PUT /api/projects/12/missions/55/composantes/512 and modify
     * line 512, which belongs to a trip of another project.
     */
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    // One unit of work: the two reads and the write belong together, and the loaded
    // ComposanteMission stays attached to Hibernate, so the setters below are already tracked.
    @Transactional
    public ComposanteResponse update(Long projectId, Long missionId, Long id, ComposanteRequest request) {
        Mission mission = missionService.loadMission(missionId);
        // Link 1 of the chain: the mission belongs to the project of the URL.
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }

        ComposanteMission composante = loadComposante(id);
        // Link 2 of the chain: the cost line belongs to that mission, and not to another trip
        // that happens to be inside a project the caller is allowed to see.
        if (!composante.getMission().getId().equals(missionId)) {
            throw new NotFoundException("Composante introuvable : " + id);
        }

        composante.setTypeComposante(request.typeComposante());
        composante.setMontant(request.montant());
        // Same upper case rule as in create(), so an update cannot introduce the lower case
        // variant of a currency that create() had normalised.
        composante.setDevise(request.devise().toUpperCase());
        composante.setDescription(request.description());

        // The object is already managed inside this transaction, so Hibernate would write the
        // change at commit even without this call. The explicit save keeps the write visible
        // when reading the code and hands back the row to map.
        return composanteMapper.toResponse(composanteRepository.save(composante));
    }

    /**
     * Deletes one cost line of one mission. Gives nothing back (the controller answers 204).
     *
     * Why a soft delete (a boolean flag) and not a real SQL DELETE: a cost line is accounting
     * data. The row stays in the table and every repository query filters on deleted = false,
     * so the amount disappears from the screens and from the totals while the history of what
     * was once declared, and by whom, stays readable for an audit.
     */
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public void delete(Long projectId, Long missionId, Long id) {
        Mission mission = missionService.loadMission(missionId);
        // The same two links of the chain as in update(): a delete is at least as dangerous as
        // an update, so it cannot be trusted with fewer checks.
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }

        ComposanteMission composante = loadComposante(id);
        if (!composante.getMission().getId().equals(missionId)) {
            throw new NotFoundException("Composante introuvable : " + id);
        }

        composante.setDeleted(true);
        composanteRepository.save(composante);
    }

    /**
     * Loads one cost line that is not deleted, or throws NotFoundException (HTTP 404).
     *
     * Why private, unlike MissionService.loadMission which is package-private: nothing outside
     * this class loads a cost line, so it stays hidden. Keeping it in one place also guarantees
     * that update() and delete() use the same "not deleted" rule and the same message; a second
     * copy would sooner or later forget the deleted = false filter and let a caller change a
     * line that was already removed.
     */
    private ComposanteMission loadComposante(Long id) {
        return composanteRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Composante introuvable : " + id));
    }
}

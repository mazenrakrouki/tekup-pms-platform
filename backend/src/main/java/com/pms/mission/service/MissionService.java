package com.pms.mission.service;

import com.pms.mission.dto.MissionRequest;
import com.pms.mission.dto.MissionResponse;
import com.pms.mission.entity.Mission;
import com.pms.mission.mapper.MissionMapper;
import com.pms.mission.repository.MissionRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * WHAT THIS FILE IS
 * All the business rules for a "mission": one work trip made by one user for one project
 * (purpose, place, start date, end date). The money spent on that trip is handled by the
 * sister class {@link ComposanteService}.
 *
 * WHERE IT SITS IN THE FLOW
 * MissionController (URLs under /api/projects/{projectId}/missions)
 *   -> MissionService (this file: permission check, data checks, read and write)
 *   -> MissionRepository / ProjectRepository / UserRepository (the SQL)
 *   -> MissionMapper, which turns a Mission entity into the MissionResponse record
 *      (a DTO, that is a small flat object made only to be sent back as JSON).
 * {@link ComposanteService} lives in the same package and calls {@link #loadMission(Long)}
 * here, so both services load a mission in exactly the same way.
 *
 * WHY IT EXISTS
 * If this class disappeared, the controller would have to carry the permission checks, the
 * date rule and the "a developer sees only his own trips" filter. Two things would break:
 *  - the permission checks must sit on the service and not on the controller, so that every
 *    caller is protected and not only an HTTP request (dynamic RBAC, ADR-001);
 *  - the same filter would have to be copied into every future caller, and one forgotten
 *    copy is a data leak.
 *
 * WHAT IS ALREADY DONE BEFORE ANY METHOD HERE RUNS (ADR-021)
 * Every URL of this module matches /api/projects/{id}/**, so ProjectScopeInterceptor has
 * already answered 403 for a user who is outside the perimeter of that project (neither its
 * manager nor a member of its team). A user who holds VIEW_ALL_PROJECTS, typically the
 * director, passes straight through that check. So this class never has to ask "may this user reach this project at all"; it only
 * asks "may this user do this action, and on which rows".
 */
@Service
@RequiredArgsConstructor
public class MissionService {

    // Lombok's @RequiredArgsConstructor (on the class above) writes the constructor that takes
    // these four final fields, and Spring passes the real beans into it at start-up.
    // Why constructor injection rather than @Autowired on each field: the fields stay final, so
    // nothing can swap a repository at runtime, and a unit test can build the service with fake
    // repositories in one line. With field injection the test would need reflection.
    private final MissionRepository missionRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository    userRepository;
    private final MissionMapper     missionMapper;

    /**
     * Returns the missions of one project that are not deleted, oldest start date first.
     *
     * What it gives back: a list of MissionResponse records, ready to be serialised to JSON.
     *
     * Why written this way rather than "just select everything": UC-21 says a developer may
     * only see his own trips. The choice between the two queries is made on a CAPABILITY the
     * user holds, never on the name of his role (ADR-001), and the filter is put inside the
     * SQL query instead of being applied on the list in memory. Filtering in memory would read
     * every row of the project into the application first, which is both slower and wrong the
     * day this endpoint becomes paginated (page 1 of 20 rows would return only the 3 rows of
     * that page that belong to the user).
     */
    // @PreAuthorize makes Spring check, BEFORE the body runs, that the permission list of the
    // logged-in user contains VIEW_MISSION. The authorities are the permission codes themselves
    // (JwtAuthenticationFilter maps each permission of the role to one authority), so no role
    // name is ever tested here (ADR-001).
    // Without it, any authenticated account that guesses the URL could read the travel plan of
    // a project, for example a resource manager who has no business with missions.
    @PreAuthorize("hasAuthority('VIEW_MISSION')")
    // @Transactional(readOnly = true) runs the whole method inside one read-only database
    // transaction. Why: the reads below must see one single, stable picture of the data, and
    // readOnly tells Hibernate it does not need to keep a copy of each row to detect changes.
    // It is also a safety belt: without readOnly, a stray setter on a loaded Mission would be
    // flushed at the end of the method and silently change a row that a "find" must never touch.
    @Transactional(readOnly = true)
    public List<MissionResponse> findByProject(Long projectId) {
        // The returned project is thrown away on purpose: this line exists only to prove the
        // project exists and is not deleted, and to answer 404 if it does not.
        // Without it, asking for project 999 would quietly return an empty list, and the user
        // would read "this project has no mission" instead of "this project does not exist".
        loadProject(projectId);
        if (canSeeAllMissions()) {
            // Wide view. The repository query uses JOIN FETCH on project and user, so the mapper
            // can read project.code and user.getFullName() without firing one extra SELECT per
            // mission: 50 missions cost 1 query instead of 101 (the classic N+1 problem).
            return missionMapper.toResponseList(missionRepository.findActiveByProjectId(projectId));
        }
        // Narrow view (UC-21): only the rows whose user_id is the current user.
        return missionMapper.toResponseList(
                missionRepository.findActiveByProjectIdAndUserId(projectId, currentUserId()));
    }

    /**
     * Tells whether the current user may see the missions of the whole team, or only his own.
     *
     * UC-21: the wide view (the missions of everybody on the project) is for the holders of
     * MANAGE_MISSION (the project manager) or of VIEW_ALL_PROJECTS (the director).
     * Otherwise the user sees his own missions only. The test is on a capability, never on a
     * role name (ADR-001).
     *
     * Why two capabilities and not only MANAGE_MISSION: the director holds VIEW_MISSION and
     * VIEW_ALL_PROJECTS but NOT MANAGE_MISSION, because he does not create trips. Testing
     * MANAGE_MISSION alone would push the director into the narrow branch, and since a director
     * rarely travels on a project himself, he would open the page and see an empty list.
     * VIEW_ALL_PROJECTS is his existing "see the whole portfolio" capability (ADR-021).
     */
    private boolean canSeeAllMissions() {
        return hasAuthority("MANAGE_MISSION") || hasAuthority("VIEW_ALL_PROJECTS");
    }

    /**
     * Returns true when the logged-in user carries the given permission code.
     *
     * Why hand-written instead of a second @PreAuthorize: @PreAuthorize can only say yes or no
     * to the whole call. Here the answer must change the QUERY, not block the call, so the
     * information is needed as a plain boolean inside the method.
     */
    private boolean hasAuthority(String code) {
        // SecurityContextHolder holds the authentication of the request being served, in a
        // variable attached to the current thread. That authentication is null when there is no
        // logged-in user, for example in a scheduled job or in a test that does not set up
        // security; calling getAuthorities() on null would throw a NullPointerException and turn
        // into a 500. Hence the null test below.
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // The stream walks the authorities and anyMatch stops at the first hit, so a user with
        // 15 permissions does not pay for 15 comparisons when the right one comes first.
        // equals() is called on the constant (code.equals(...)) and not on a.getAuthority(), so
        // an authority that would somehow be null cannot throw here.
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    /**
     * Id of the current user; -1 when it cannot be found, so that no mission is returned.
     *
     * Why -1 and not an exception: this value is used as a filter in a WHERE clause, and an id
     * that matches no row fails closed, which is the safe direction. Returning null would also
     * match nothing today, but a later change could read that null as "no filter at all" and
     * return every row of the project.
     */
    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return -1L;
        // auth.getName() is the e-mail, because JwtAuthenticationFilter builds the
        // authentication with the e-mail as the principal. The account is read again from the
        // database instead of trusting an id carried inside the token, so an account deleted
        // after the token was issued is not found and -1 is used.
        return userRepository.findActiveByEmailWithRole(auth.getName())
                .map(User::getId)
                .orElse(-1L);
    }

    /**
     * Creates one mission for a project and gives back the saved row as a MissionResponse.
     *
     * Why the project and the user are read from the database instead of being built from the
     * ids of the request: the load proves both rows exist and are not deleted, and the mapper
     * can then read project.code and the user full name from the object that was just saved.
     * Building a bare Project holding only an id would save fine, but the response would come
     * back with a null project code and the screen would show an empty column.
     */
    // Creating a trip is reserved to MANAGE_MISSION, which the matrix grants to the project
    // manager. A developer holds VIEW_MISSION only, so his POST is refused with 403 before the
    // body runs; without this line he could book a trip for himself.
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    // One unit of work for the whole method: the two reads and the insert either all succeed or
    // none of them is kept. It also keeps one persistence context open from the first read to
    // the mapping of the result.
    @Transactional
    public MissionResponse create(Long projectId, MissionRequest request) {
        Project project = loadProject(projectId);
        User user = loadUser(request.userId());
        validateDates(request);

        // The builder (Lombok @Builder on the entity) names every value it sets, so two fields
        // of the same type cannot be swapped by mistake. With a constructor taking
        // (dateDebut, dateFin) nothing would stop a caller from passing them in the wrong order.
        // No id is set here: the database gives it (BIGSERIAL / IDENTITY), and the audit columns
        // created_at and created_by are filled by the JPA auditing listener of BaseEntity.
        Mission mission = Mission.builder()
                .project(project)
                .user(user)
                .objet(request.objet())
                .lieu(request.lieu())
                .dateDebut(request.dateDebut())
                .dateFin(request.dateFin())
                .build();

        // save() gives back the instance that now carries the generated id; the mapper needs
        // that id so the response tells the frontend which row was created.
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    /**
     * Updates one mission of a project and gives back its new state.
     *
     * Why the mission is loaded first and its project compared with the projectId of the URL:
     * the scope interceptor (ADR-021) only reads the {projectId} written in the URL, it cannot
     * know which project mission 77 really belongs to. Without the comparison, a project
     * manager allowed on project 12 could send PUT /api/projects/12/missions/77 and edit a
     * mission of project 40, which he must not touch.
     *
     * Why the answer is 404 "not found" and not 403 "forbidden": a 403 would confirm that
     * mission 77 exists somewhere else in the application. The 404 says nothing.
     */
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    // One unit of work: the read at the top and the write at the bottom belong together, and the
    // Mission stays attached to Hibernate in between, so the setters below are already tracked.
    @Transactional
    public MissionResponse update(Long projectId, Long id, MissionRequest request) {
        Mission mission = loadMission(id);
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + id);
        }
        // The dates are checked before anything is changed, so a bad request leaves the row
        // exactly as it was.
        validateDates(request);

        // The user of a mission can change (the trip is handed over to somebody else); loadUser
        // refuses an account that no longer exists or was deactivated.
        User user = loadUser(request.userId());
        mission.setUser(user);
        mission.setObjet(request.objet());
        mission.setLieu(request.lieu());
        mission.setDateDebut(request.dateDebut());
        mission.setDateFin(request.dateFin());

        // The object is already managed inside this transaction, so Hibernate would write these
        // changes at commit even without this call. The explicit save keeps the write visible
        // when reading the code and hands back the row to map.
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    /**
     * Deletes one mission of a project. Gives nothing back (the controller answers 204).
     *
     * Why a soft delete (a boolean flag) and not a real SQL DELETE: the row is kept and every
     * repository query filters on deleted = false. A real DELETE would fail anyway as long as
     * cost lines point at this mission through the foreign key fk_comp_mission, and it would
     * destroy accounting history that an audit may ask for later. With the flag, a mission
     * deleted by mistake comes back by flipping one boolean.
     */
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Mission mission = loadMission(id);
        // Same protection as in update(): the mission must really belong to the project named in
        // the URL, otherwise the manager of project 12 could delete a mission of project 40.
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + id);
        }
        mission.setDeleted(true);
        missionRepository.save(mission);
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Refuses a trip that ends before it starts.
     *
     * Why check here when the table already has the CHECK constraint chk_mission_dates (V10):
     * the constraint is the last safety net, but it only fires at commit and surfaces as a
     * technical error (HTTP 500) written for a DBA. Checking here throws BusinessRuleException,
     * which the global handler turns into HTTP 422 with a sentence the user can read.
     * Example of what it catches: start 10 March, end 2 March.
     * Two equal dates are accepted on purpose: a one-day trip starts and ends the same day.
     */
    private void validateDates(MissionRequest request) {
        if (request.dateFin().isBefore(request.dateDebut())) {
            throw new BusinessRuleException("La date de fin doit être égale ou postérieure à la date de début");
        }
    }

    /**
     * Loads one mission that is not deleted, or throws NotFoundException (HTTP 404).
     *
     * Why package-private (no "private", no "public"): {@link ComposanteService} sits in the
     * same package and needs exactly this load, with the same "not deleted" rule and the same
     * error message. Private would force a second copy of the query; public would expose an
     * unguarded load to controllers and to other modules.
     *
     * Why there is no @PreAuthorize on it: it is an internal helper. When another method of this
     * same class calls it, the call does not go through the Spring security proxy, so an
     * annotation here would silently do nothing for those calls. The real guard sits on each
     * public entry point, and ComposanteService declares its own on every method.
     */
    Mission loadMission(Long id) {
        return missionRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Mission introuvable : " + id));
    }

    /**
     * Loads one project that is not deleted, or throws NotFoundException (HTTP 404).
     * Used to prove the project named in the URL really exists before reading or writing
     * anything under it.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /**
     * Loads the user a mission is given to, or throws NotFoundException (HTTP 404).
     *
     * Why the extra filter: findById is the plain JPA lookup and knows nothing about the soft
     * delete flag. Without it, a manager could book a trip for an employee who has already left
     * the company, and that trip would then land in the project costs.
     */
    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}

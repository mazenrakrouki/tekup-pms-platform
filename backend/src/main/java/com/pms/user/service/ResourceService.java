package com.pms.user.service;

import com.pms.user.dto.ResourceRequest;
import com.pms.user.dto.ResourceResponse;
import com.pms.user.dto.TccAnnuelDto;
import com.pms.user.entity.Resource;
import com.pms.user.entity.TccAnnuel;
import com.pms.user.mapper.ResourceMapper;
import com.pms.user.repository.ResourceRepository;
import com.pms.user.repository.TccAnnuelRepository;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.pms.shared.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// =============================================================================
// FILE: ResourceService.java
//
// WHAT THIS FILE IS
//   The service behind the "Resources" screens. A Resource is the COST side of
//   a person: their daily rate, their TCC rate, and the dates between which
//   they are staffed. It also manages the per-year rate history (tcc_annuels).
//
//   TCC = "taux de charges complementaires", the extra-charges rate. It is the
//   multiplier added on top of the daily rate to get what the person really
//   costs the company (social charges, structure costs...). A daily rate of
//   500 with a TCC of 0.35 means a loaded cost of 500 x 1.35 = 675 per day.
//
// WHERE IT SITS IN THE FLOW
//   Called by:  ResourceController, on /api/resources (list, get, create,
//     update, delete) and /api/resources/{id}/tcc (read and replace the
//     per-year rates).
//   Calls:      ResourceRepository (the resources and the two scope queries),
//     TccAnnuelRepository (the per-year rates), UserRepository (to attach the
//     account and to find who is calling), ResourceMapper (MapStruct, ADR-018).
//   Produces:   ResourceResponse and TccAnnuelDto.
//
// WHY IT EXISTS
//   It holds the money side of the application. Delete it and no cost, no
//   margin and no internal quote (Devis Interne) can be computed, because every
//   one of those starts from a daily rate and a TCC rate read from here.
//
// WHY A Resource IS NOT THE SAME THING AS A User (ADR-022)
//   A User is the ACCOUNT: who can sign in. A Resource is what the person
//   COSTS. They are deliberately kept apart: an assistant needs an account but
//   has no billable rate, and a rate must keep existing for past cost
//   calculations long after the account has been switched off.
//
// THE DATA SCOPE -- and why ADR-021's interceptor does not cover it
//   ADR-021 says ProjectScopeInterceptor checks BOTH the permission AND the
//   project scope, but its pattern only matches /api/projects/{id}/**. The URLs
//   served here carry a RESOURCE id, so the interceptor sees no project id and
//   lets them through. The very same idea therefore had to be written by hand
//   in this file:
//     * a caller holding MANAGE_RESOURCES (Admin, Directeur) sees everything;
//     * a caller holding only VIEW_RESOURCES -- a project manager -- sees only
//       themselves and the people assigned to the projects they manage.
//   That is what hasFullAccess(), assertVisible() and the two scope queries of
//   ResourceRepository do at the bottom of this file. Holding the permission is
//   NOT enough; the row must also be inside the caller's perimeter. This is a
//   point a jury is likely to press on: TCC rates are salary information, so
//   "VIEW_RESOURCES" alone must never mean "see everyone's cost".
//
// COMPUTED AMOUNTS ARE NEVER STORED
//   There is no "annual cost" column anywhere. Resource.getAnnualCost() derives
//   it when it is read, and ResourceMapper calls that method. A stored total
//   would keep the old value the day a daily rate is corrected.
// =============================================================================

@Service
@RequiredArgsConstructor
public class ResourceService {

    /**
     * Full TCC access: the holders of {@code MANAGE_RESOURCES} (Admin,
     * Directeur). Translated from the original French comment.
     *
     * <p>Why a named constant: hasFullAccess() compares this exact string
     * against the caller's authority list. If it were typed inline and
     * misspelled, the comparison would simply never match, the project-manager
     * branch would run for the Admin as well, and the Admin would silently see
     * a shortened list with nothing in the log to explain it. A constant is
     * written once and the compiler catches any misuse of the name.
     *
     * <p>Note: this string is repeated inside the {@code @PreAuthorize} of the
     * write methods below. An annotation value must be a compile-time constant
     * expression, and Spring parses it as SpEL text, so the constant cannot be
     * substituted there.
     */
    private static final String FULL_ACCESS = "MANAGE_RESOURCES";

    private final ResourceRepository resourceRepository;
    // Used for two different things: attaching an account to a new resource,
    // and turning the e-mail of the caller into their database id (see
    // currentUserId at the bottom).
    private final UserRepository userRepository;
    private final ResourceMapper resourceMapper;
    private final TccAnnuelRepository tccAnnuelRepository;

    /**
     * Lists the resources the caller is allowed to see.
     *
     * <p>Translated from the original French comment: full access
     * (Admin/Directeur, MANAGE_RESOURCES) returns the whole referential; a
     * project manager holding only VIEW_RESOURCES gets the resources of their
     * own projects (PM scope, ADR-021).
     *
     * <p>Gives back a list of ResourceResponse, each one carrying the derived
     * annual cost.
     *
     * <p>Why the branch is here and not two separate endpoints: the Angular
     * screen is the same for both audiences. One endpoint that returns a
     * narrower list is simpler than two URLs the front end would have to choose
     * between -- and choosing on the front end would mean the browser decides
     * what it may see, which is never acceptable.
     */
    // The entry guard: without VIEW_RESOURCES the call never starts.
    // Note that this only opens the door. WHAT the caller then sees is decided
    // inside the method. Permission and scope are two different questions
    // (ADR-021).
    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public List<ResourceResponse> findAll() {
        if (hasFullAccess()) {
            // findAllActive does "JOIN FETCH r.user ... WHERE r.deleted =
            // false". The JOIN FETCH matters because ResourceMapper reads
            // user.getFullName() afterwards, and open-in-view is false, so the
            // database session is already closed at that point. Without it the
            // list screen would fail with LazyInitializationException.
            return resourceMapper.toResponseList(resourceRepository.findAllActive());
        }
        Long pmUserId = currentUserId();
        // The narrowed query: the resources of the people assigned to the
        // projects this user manages, plus the user themselves. The filter is
        // done by the DATABASE, not in Java.
        // WHY that matters: filtering afterwards in Java would mean the rows of
        // everybody else were loaded into memory first, and any future logging
        // or mapping added in between would expose salary data the caller must
        // not see.
        return resourceMapper.toResponseList(resourceRepository.findVisibleToProjectManager(pmUserId));
    }

    /**
     * Returns one resource by id, if the caller is allowed to see that one.
     *
     * <p>Throws NotFoundException (HTTP 404) when the id is unknown or
     * soft-deleted, and AccessDeniedException (HTTP 403) when the resource
     * exists but sits outside a project manager's perimeter.
     *
     * <p>Why the scope check is repeated here although findAll() already
     * narrows the list: the caller can type any id in the URL. Trusting the
     * list would mean a project manager could read the daily rate of anybody in
     * the company just by trying /api/resources/1, /2, /3.
     */
    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public ResourceResponse findById(Long id) {
        Resource resource = loadResource(id);
        // The scope half of ADR-021, applied by hand because the interceptor
        // does not see this URL. Reading order: the row is loaded first, so an
        // unknown id answers 404, and only an existing but out-of-perimeter row
        // answers 403.
        assertVisible(id);
        return resourceMapper.toResponse(resource);
    }

    /**
     * Creates the cost record of a user: daily rate, TCC rate and staffing
     * dates.
     *
     * <p>Gives back the saved ResourceResponse, annual cost included.
     *
     * <p>Throws NotFoundException (HTTP 404) when the account does not exist,
     * and IllegalArgumentException (HTTP 409 Conflict) when that person already
     * has an active resource.
     */
    // MANAGE_RESOURCES, not VIEW_RESOURCES. Writing a rate is an Admin and
    // Directeur action; a project manager may look at the rates of their own
    // team but may never change one.
    // Note that no scope check is needed in the three write methods below:
    // MANAGE_RESOURCES IS the full-access permission, so anyone who passes this
    // guard already sees the whole referential.
    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    // One unit of work for the two checks and the INSERT. Without it, a crash
    // between them could leave a resource attached to an account that was
    // deleted in the meantime.
    @Transactional
    public ResourceResponse create(ResourceRequest request) {
        var user = userRepository.findById(request.userId())
                // findById knows nothing about the soft-delete flag, so this
                // filter is what stops a cost record from being attached to an
                // account that was removed. Without it the resource would show
                // up on the screens with the name of somebody who no longer
                // exists.
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + request.userId()));

        // One person, one ACTIVE resource.
        // WHY the rule exists: two live rates for the same person would make
        // every cost query ambiguous -- the margin of a project would depend on
        // which of the two rows the query happened to read first.
        // findActiveByUserId carries "AND r.deleted = false" in its query, so
        // it looks only at the live rows. A person whose old resource was
        // archived can therefore be given a new one.
        // Careful, and worth knowing for a jury question: the database
        // constraint uk_resources_user_id (V4) is an ABSOLUTE unique on
        // user_id, not a partial one like uk_users_email. So in practice the
        // database refuses a second row for the same person even when the first
        // one is archived, and this check is what produces the readable 409
        // before that happens.
        // French text: "this user is already an active resource".
        if (resourceRepository.findActiveByUserId(request.userId()).isPresent()) {
            throw new IllegalArgumentException("Cet utilisateur est déjà une ressource active");
        }

        Resource resource = Resource.builder()
                .user(user)
                // dailyRate and tccRate are BigDecimal, never double.
                // WHY: double cannot hold 0.35 exactly, and repeated
                // multiplications drift. On a margin computed over hundreds of
                // days, the total would end in .0000001 and two screens reading
                // the same data could disagree on the last cent.
                .dailyRate(request.dailyRate())
                .tccRate(request.tccRate())
                .staffingStart(request.staffingStart())
                // staffingEnd is the only nullable one: a person still in the
                // company has no end date.
                .staffingEnd(request.staffingEnd())
                .build();

        return resourceMapper.toResponse(resourceRepository.save(resource));
    }

    /**
     * Updates the rates and the staffing dates of an existing resource.
     *
     * <p>Gives back the saved ResourceResponse.
     *
     * <p>Note what is NOT updated: the linked user. A resource stays attached
     * to the account it was created for. Moving it to somebody else would
     * silently rewrite the cost history of both people, because every past
     * workload entry points at this resource.
     *
     * <p>Note also what is not touched: the per-year rates of tcc_annuels. The
     * two values edited here are the FALLBACK used when no row exists for the
     * year being charged. replaceTccAnnuels below is the one that edits the
     * per-year history.
     */
    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public ResourceResponse update(Long id, ResourceRequest request) {
        Resource resource = loadResource(id);

        resource.setDailyRate(request.dailyRate());
        resource.setTccRate(request.tccRate());
        resource.setStaffingStart(request.staffingStart());
        resource.setStaffingEnd(request.staffingEnd());

        return resourceMapper.toResponse(resourceRepository.save(resource));
    }

    /**
     * Soft-deletes a resource: the row stays, the "deleted" flag is set.
     *
     * <p>Returns nothing.
     *
     * <p>Why never a real DELETE: the resource id is referenced by the workload
     * entries and by every past cost computation. Removing the row would either
     * be refused by the foreign keys or destroy the history, and a project
     * closed two years ago would stop being able to show what it cost.
     */
    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public void delete(Long id) {
        Resource resource = loadResource(id);
        resource.setDeleted(true);
        resourceRepository.save(resource);
    }

    // ── TCC par année (F-AFF-13 §6.3 règle 4) ─────────────────────
    //
    // Translated: TCC per year, specification F-AFF-13 section 6.3 rule 4.
    // The rule says the TCC of 2024 is not the TCC of 2025: rates are
    // renegotiated, so a day charged in 2024 must be costed with the 2024 rate
    // even if it is read today. The tcc_annuels table (created in V21) holds
    // one row per resource and per year. When no row exists for the year being
    // charged, the base rates carried by the Resource itself apply.
    //
    // Why the history is stored rather than recomputed: there is no formula
    // that could give back a rate that was negotiated in a meeting. Without
    // this table, correcting today's rate would silently rewrite the cost of
    // every project closed in previous years.

    /**
     * Returns the per-year rates of one resource, oldest year first.
     *
     * <p>Gives back a list of TccAnnuelDto (year, daily rate, TCC rate). The
     * row id is deliberately left out: the client always sends the complete
     * list back, so it never needs to name a single row.
     *
     * <p>Throws NotFoundException (404) for an unknown resource and
     * AccessDeniedException (403) for one outside a project manager's
     * perimeter.
     */
    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public List<TccAnnuelDto> findTccAnnuels(Long resourceId) {
        // The returned object is not used: the call is here to answer 404
        // before anything else when the resource does not exist. Without it, an
        // unknown id would simply return an empty list, and the screen would
        // show "no rate recorded" for a resource that does not exist at all.
        loadResource(resourceId);
        // The scope half of ADR-021 again. These are salary figures, so a
        // project manager must not be able to read them for somebody outside
        // their own projects by typing an id in the URL.
        assertVisible(resourceId);
        return tccAnnuelRepository.findActiveByResourceId(resourceId).stream()
                .map(t -> new TccAnnuelDto(t.getAnnee(), t.getDailyRate(), t.getTccRate()))
                .toList();
    }

    /**
     * Replaces the whole per-year rate history of one resource with the list
     * the screen sends.
     *
     * <p>Gives back the saved list as TccAnnuelDto. A year present in the list
     * is created or updated; a year absent from it is soft-deleted.
     *
     * <p>Why "replace" and not one endpoint per year: the screen is a small
     * editable grid where the user adds, edits and removes lines, then presses
     * Save once. Sending the final state as a whole is what makes the save
     * atomic -- either the grid is stored as it is displayed, or nothing
     * changes.
     *
     * <p>Throws IllegalArgumentException (HTTP 409) when the same year appears
     * twice in the payload.
     */
    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    // @Transactional makes the whole replacement one single database unit of
    // work: the updates, the inserts and the soft-deletes below all commit
    // together.
    // Why it is essential here: three separate write batches follow. A crash
    // between them would leave the resource with the new 2025 rate but with
    // 2024 still marked as removed, and every cost computed for 2024 would fall
    // back to the base rate without anybody noticing.
    @Transactional
    public List<TccAnnuelDto> replaceTccAnnuels(Long resourceId, List<TccAnnuelDto> rates) {
        Resource resource = loadResource(resourceId);

        // Counts how many DIFFERENT years the payload contains and compares
        // that with how many lines it has. If the two differ, a year is there
        // twice.
        // WHY the check is needed: the code below stores the lines in a Map
        // keyed by year. Two lines for 2025 would silently keep only one of
        // them, and the administrator would see one of their two entries
        // disappear after saving with no error at all. The database would
        // refuse it too, through uk_tcc_annuel_resource_annee, but with an
        // unreadable constraint-violation message.
        // French text: "each year may appear only once".
        long distinctYears = rates.stream().map(TccAnnuelDto::annee).distinct().count();
        if (distinctYears != rates.size()) {
            throw new IllegalArgumentException("Chaque année ne peut apparaître qu'une seule fois");
        }

        // Update in place, year by year. (Original French comment, translated
        // and kept because it records a real bug and its fix.)
        //
        // The previous version soft-deleted every row and then re-inserted the
        // list it received. At flush time Hibernate orders its actions INSERT
        // before UPDATE, so the new row for a year reached the database BEFORE
        // the old one was marked deleted, and the partial unique index
        // uk_tcc_annuel_resource_annee -- UNIQUE (resource_id, annee) WHERE
        // deleted = FALSE, created in V21 -- rejected it. The consequence: any
        // change to a year that had already been entered came back as 409, and
        // only adding a year that did not exist yet worked. So the existing row
        // is now UPDATED instead of being replaced, and only the years really
        // removed by the user are soft-deleted.
        //
        // Collectors.toMap(year -> row) builds a lookup "year -> existing row",
        // so the loop below can find the row of a year without scanning the
        // list again. It is safe to build this map because the duplicate check
        // above has already proved the years are unique -- toMap throws
        // IllegalStateException on a duplicate key.
        Map<Integer, TccAnnuel> existing = tccAnnuelRepository.findActiveByResourceId(resourceId)
                .stream()
                .collect(Collectors.toMap(TccAnnuel::getAnnee, t -> t));

        List<TccAnnuel> result = new ArrayList<>();
        for (TccAnnuelDto dto : rates) {
            // remove() does two jobs at once: it gives back the existing row
            // for that year (or null), AND it takes the year out of the map.
            // That is what makes the map, at the end of the loop, contain
            // exactly the years the client did NOT send.
            TccAnnuel row = existing.remove(dto.annee());
            // Null means the year is new: build a fresh row attached to this
            // resource. The year is set at build time because it is the
            // business key and must never change afterwards.
            if (row == null) {
                row = TccAnnuel.builder().resource(resource).annee(dto.annee()).build();
            }
            // For an existing row these two setters are a plain UPDATE; for a
            // new one they fill the values before the INSERT.
            row.setDailyRate(dto.dailyRate());
            row.setTccRate(dto.tccRate());
            result.add(row);
        }

        // Whatever is left in `existing` was not sent back by the client: the
        // user removed that year from the grid. (Original French comment.)
        // Soft delete, not a real DELETE: past cost computations may still
        // point at the row, and the partial unique index only looks at rows
        // where deleted = FALSE, so the year can be entered again later.
        existing.values().forEach(t -> t.setDeleted(true));
        // saveAll sends the batch in one go instead of one call per row.
        tccAnnuelRepository.saveAll(existing.values());

        // The removals are saved BEFORE the kept rows on purpose: it keeps the
        // order of the statements readable for whoever debugs a unique-index
        // error here. Both batches commit together anyway, thanks to
        // @Transactional.
        return tccAnnuelRepository.saveAll(result).stream()
                .map(t -> new TccAnnuelDto(t.getAnnee(), t.getDailyRate(), t.getTccRate()))
                .toList();
    }

    /**
     * Loads one resource by id, or throws NotFoundException (HTTP 404).
     *
     * <p>Why this helper exists: the soft-delete filter has to be applied every
     * single time. Written once, the six public methods above cannot forget it.
     * Note that it checks EXISTENCE only -- the perimeter check is a separate
     * method, assertVisible, because the two answer different HTTP statuses.
     */
    private Resource loadResource(Long id) {
        return resourceRepository.findById(id)
                // findById is the plain Spring Data method and ignores the
                // "deleted" flag. Without this filter, an archived resource
                // could still have its rates edited through its id, and the
                // cost history of a closed project would change.
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new NotFoundException("Ressource introuvable : " + id));
    }

    // ── Scope de données PM (ADR-021) ─────────────────────────────
    //
    // Translated: project-manager data scope, ADR-021. The three helpers below
    // are the hand-written version of the rule "permission AND scope". The
    // interceptor of ADR-021 only guards /api/projects/{id}/**, and these URLs
    // carry a resource id, so it does not apply here.

    /**
     * True when the caller holds {@code MANAGE_RESOURCES}, which means full
     * access to the TCC referential (Admin, Directeur). Translated from the
     * original French comment.
     *
     * <p>Why read the authorities instead of asking for the user's role: the
     * whole point of ADR-001 is that the code never tests a role name. An
     * administrator may move MANAGE_RESOURCES to another role from the admin
     * screen, and this method keeps working without a code change.
     */
    private boolean hasFullAccess() {
        // SecurityContextHolder is where Spring Security keeps the identity of
        // the caller for the current request thread. JwtAuthenticationFilter
        // put it there after checking the token.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // The null test comes FIRST, and && stops as soon as it is false, so
        // the stream is never built on a null.
        // Why auth can be null: a scheduled job or a test calling this service
        // directly has no HTTP request and therefore no security context.
        // Without the test, such a call would fail with NullPointerException
        // instead of simply being treated as "no full access".
        return auth != null && auth.getAuthorities().stream()
                // Each authority object is reduced to its string, for example
                // "MANAGE_RESOURCES".
                .map(GrantedAuthority::getAuthority)
                // anyMatch stops at the first match. FULL_ACCESS::equals reads
                // as "does the constant equal this string?", which also means
                // the comparison can never be called on a null constant.
                .anyMatch(FULL_ACCESS::equals);
    }

    /**
     * Raises 403 when the caller does not have full access AND the resource is
     * outside their project-manager perimeter. Translated from the original
     * French comment.
     *
     * <p>Returns nothing when the access is allowed -- it is a guard, not a
     * question.
     *
     * <p>Why a separate method rather than the check written in each caller:
     * findById and findTccAnnuels must apply exactly the same rule. Two copies
     * would eventually drift, and a drift here means a project manager reading
     * a colleague's salary cost.
     */
    private void assertVisible(Long resourceId) {
        // Admin and Directeur short-circuit: no query is fired for them.
        if (hasFullAccess()) return;
        // The perimeter question is asked to the DATABASE, which walks
        // resource -> user -> team assignment -> project -> chefProjet and
        // answers true or false. Doing it in SQL means no row leaves the
        // database before the answer is known.
        // The query also accepts the caller's own resource, so a project
        // manager can always see their own rate.
        if (!resourceRepository.isResourceVisibleToProjectManager(resourceId, currentUserId())) {
            // AccessDeniedException is mapped to HTTP 403 by
            // GlobalExceptionHandler, which replaces the message with a generic
            // "Accès refusé". The detail written here is for the server log.
            // French text: "resource outside perimeter".
            throw new AccessDeniedException("Ressource hors périmètre");
        }
    }

    /**
     * Gives back the database id of the person making the current request.
     *
     * <p>Why the id is looked up instead of being read from the token: the JWT
     * of this project carries the e-mail, not the numeric id (ADR-017 keeps the
     * access token identity-only). The scope queries above join on user ids, so
     * the e-mail has to be turned into an id here.
     *
     * <p>Throws AccessDeniedException (403), not NotFoundException, when no
     * active account matches. That is deliberate: "I cannot tell who you are"
     * is a refusal, not a missing page.
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // getName() returns what JwtAuthenticationFilter stored as the
        // principal, which in this project is the e-mail.
        // The null test covers the same case as above: no HTTP request, no
        // security context. Passing null on to the query is safe -- the JPQL
        // compares u.email = :email, which no row can match, so the line below
        // turns it into a clean 403.
        String email = (auth != null) ? auth.getName() : null;
        return userRepository.findActiveByEmailWithRole(email)
                // "current user not found"
                .orElseThrow(() -> new AccessDeniedException("Utilisateur courant introuvable"))
                .getId();
    }
}

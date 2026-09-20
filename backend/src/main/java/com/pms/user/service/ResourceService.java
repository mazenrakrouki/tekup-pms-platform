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

// Service behind the "Resources" screens. A Resource is the COST side of a person (daily
// rate, TCC rate — the loading coefficient on top of the daily rate, e.g. 500 x 1.35 for a
// 0.35 TCC — and staffing dates), plus the per-year rate history (tcc_annuels). Kept apart
// from User (ADR-022): an account can exist without a billable rate.
// ADR-021's ProjectScopeInterceptor only matches /api/projects/{id}/**, not these
// resource-id URLs, so the data scope is hand-written here: MANAGE_RESOURCES sees
// everything, VIEW_RESOURCES alone (a project manager) sees only their own projects'
// people plus themselves (see hasFullAccess/assertVisible below). Annual cost is always
// derived (Resource.getAnnualCost()), never stored.

@Service
@RequiredArgsConstructor
public class ResourceService {

    /**
     * Holders of {@code MANAGE_RESOURCES} (Admin, Directeur) get full TCC access.
     *
     * <p>A named constant so hasFullAccess() can't silently mismatch on a typo. Repeated
     * literally inside the {@code @PreAuthorize} below because SpEL needs a compile-time
     * constant expression there.
     */
    private static final String FULL_ACCESS = "MANAGE_RESOURCES";

    private final ResourceRepository resourceRepository;
    // Also used to turn the caller's e-mail into their database id (see currentUserId).
    private final UserRepository userRepository;
    private final ResourceMapper resourceMapper;
    private final TccAnnuelRepository tccAnnuelRepository;

    /**
     * Lists the resources the caller is allowed to see: the whole referential for
     * MANAGE_RESOURCES, or just their own projects' people for VIEW_RESOURCES alone
     * (PM scope, ADR-021).
     */
    // VIEW_RESOURCES only opens the door; WHAT is seen is decided inside the method
    // (permission and scope are separate questions, ADR-021).
    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public List<ResourceResponse> findAll() {
        if (hasFullAccess()) {
            // JOIN FETCH r.user is required: ResourceMapper reads user.getFullName()
            // afterward, and open-in-view is false.
            return resourceMapper.toResponseList(resourceRepository.findAllActive());
        }
        Long pmUserId = currentUserId();
        // Filtered by the database, not in Java — filtering afterward would mean salary
        // data for everybody else was loaded into memory first.
        return resourceMapper.toResponseList(resourceRepository.findVisibleToProjectManager(pmUserId));
    }

    /**
     * Returns one resource by id, if the caller is allowed to see that one.
     *
     * <p>Throws NotFoundException (404) for an unknown/deleted id, AccessDeniedException
     * (403) for one outside a project manager's perimeter — checked separately from
     * findAll() because the caller can type any id directly in the URL.
     */
    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public ResourceResponse findById(Long id) {
        Resource resource = loadResource(id);
        // Loaded before the scope check so an unknown id answers 404 and an out-of-perimeter
        // one answers 403.
        assertVisible(id);
        return resourceMapper.toResponse(resource);
    }

    /**
     * Creates the cost record of a user: daily rate, TCC rate and staffing dates.
     *
     * <p>Throws NotFoundException (404) when the account does not exist, and
     * IllegalArgumentException (409) when that person already has an active resource.
     */
    // MANAGE_RESOURCES, not VIEW_RESOURCES: writing a rate is Admin/Directeur only. No scope
    // check needed on the write methods below — MANAGE_RESOURCES already means full access.
    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public ResourceResponse create(ResourceRequest request) {
        var user = userRepository.findById(request.userId())
                // findById ignores soft delete; without this filter a resource could be
                // attached to an account that no longer exists.
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + request.userId()));

        // One person, one ACTIVE resource — two live rates would make cost queries
        // ambiguous. uk_resources_user_id (V4) is an ABSOLUTE unique constraint, so this
        // check is what turns a would-be DB error into a readable 409.
        if (resourceRepository.findActiveByUserId(request.userId()).isPresent()) {
            throw new IllegalArgumentException("Cet utilisateur est déjà une ressource active");
        }

        Resource resource = Resource.builder()
                .user(user)
                // BigDecimal, not double: double can't hold 0.35 exactly and repeated
                // multiplications would drift the margin by fractions of a cent.
                .dailyRate(request.dailyRate())
                .tccRate(request.tccRate())
                .staffingStart(request.staffingStart())
                // Only nullable field: a person still in the company has no end date.
                .staffingEnd(request.staffingEnd())
                .build();

        return resourceMapper.toResponse(resourceRepository.save(resource));
    }

    /**
     * Updates the rates and staffing dates of an existing resource. Never touches the
     * linked user (moving it would silently rewrite both people's cost history) or the
     * per-year rates in tcc_annuels — the values here are only the FALLBACK for years with
     * no override; see replaceTccAnnuels below for the per-year history.
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
     * Soft-deletes a resource: the row stays, only the "deleted" flag is set. Never a real
     * DELETE — the id is referenced by workload entries and past cost computations, which
     * must still be explainable after a person leaves.
     */
    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public void delete(Long id) {
        Resource resource = loadResource(id);
        resource.setDeleted(true);
        resourceRepository.save(resource);
    }

    // TCC per year (F-AFF-13 §6.3 rule 4): rates are renegotiated yearly, so a day charged
    // in 2024 must cost at the 2024 rate even when read today. tcc_annuels (V21) holds one
    // row per resource and year; when none exists, the base rates on Resource apply.
    // Stored rather than recomputed — there's no formula to reconstruct a rate that was
    // negotiated in a meeting.

    /**
     * Per-year rates of one resource, oldest first, as TccAnnuelDto (year, daily rate, TCC
     * rate — no row id, since the client always resends the complete list).
     *
     * <p>Throws NotFoundException (404) for an unknown resource, AccessDeniedException
     * (403) for one outside a project manager's perimeter.
     */
    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public List<TccAnnuelDto> findTccAnnuels(Long resourceId) {
        // Result unused: only here to answer 404 for an unknown resource before scope check.
        loadResource(resourceId);
        assertVisible(resourceId);
        return tccAnnuelRepository.findActiveByResourceId(resourceId).stream()
                .map(t -> new TccAnnuelDto(t.getAnnee(), t.getDailyRate(), t.getTccRate()))
                .toList();
    }

    /**
     * Replaces the whole per-year rate history of one resource with what the grid screen
     * sends: a year present is created/updated, a year absent is soft-deleted. Replace
     * rather than one endpoint per year keeps the save atomic for the editable grid.
     *
     * <p>Throws IllegalArgumentException (409) when the same year appears twice.
     */
    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public List<TccAnnuelDto> replaceTccAnnuels(Long resourceId, List<TccAnnuelDto> rates) {
        Resource resource = loadResource(resourceId);

        // Duplicate-year guard: without it two lines for the same year would silently
        // collapse into one via the Map below, or hit uk_tcc_annuel_resource_annee with an
        // unreadable constraint error.
        long distinctYears = rates.stream().map(TccAnnuelDto::annee).distinct().count();
        if (distinctYears != rates.size()) {
            throw new IllegalArgumentException("Chaque année ne peut apparaître qu'une seule fois");
        }

        // Updated in place, year by year — a real bug and its fix. The old version
        // soft-deleted every row then re-inserted the list, but Hibernate flushes INSERTs
        // before UPDATEs, so the new row hit the partial unique index
        // uk_tcc_annuel_resource_annee before the old one was marked deleted, and editing an
        // existing year always failed with 409. Existing rows are now updated in place, and
        // only years really removed by the user are soft-deleted.
        Map<Integer, TccAnnuel> existing = tccAnnuelRepository.findActiveByResourceId(resourceId)
                .stream()
                .collect(Collectors.toMap(TccAnnuel::getAnnee, t -> t));

        List<TccAnnuel> result = new ArrayList<>();
        for (TccAnnuelDto dto : rates) {
            // remove() both fetches the existing row and drops it from the map, so whatever
            // is left afterward is exactly the years the client did not send.
            TccAnnuel row = existing.remove(dto.annee());
            if (row == null) {
                row = TccAnnuel.builder().resource(resource).annee(dto.annee()).build();
            }
            row.setDailyRate(dto.dailyRate());
            row.setTccRate(dto.tccRate());
            result.add(row);
        }

        // Whatever remains in `existing` was removed from the grid by the user.
        existing.values().forEach(t -> t.setDeleted(true));
        tccAnnuelRepository.saveAll(existing.values());

        return tccAnnuelRepository.saveAll(result).stream()
                .map(t -> new TccAnnuelDto(t.getAnnee(), t.getDailyRate(), t.getTccRate()))
                .toList();
    }

    /**
     * Loads one resource by id, or throws NotFoundException (404). Existence only — the
     * perimeter check is the separate assertVisible, since the two answer different HTTP
     * statuses.
     */
    private Resource loadResource(Long id) {
        return resourceRepository.findById(id)
                // findById ignores soft delete; without this, an archived resource's rates
                // could still be edited through its id.
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new NotFoundException("Ressource introuvable : " + id));
    }

    // Project-manager data scope (ADR-021), hand-written because the interceptor only
    // guards /api/projects/{id}/** and these URLs carry a resource id.

    /** True when the caller holds {@code MANAGE_RESOURCES} — full TCC access (Admin, Directeur). */
    private boolean hasFullAccess() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // auth can be null for a scheduled job or a test calling this service directly.
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(FULL_ACCESS::equals);
    }

    /**
     * Raises 403 when the caller lacks full access AND the resource is outside their
     * project-manager perimeter. A separate method so findById and findTccAnnuels apply
     * exactly the same rule.
     */
    private void assertVisible(Long resourceId) {
        if (hasFullAccess()) return;
        // The perimeter question is asked in SQL, walking resource -> user -> team
        // assignment -> project -> chefProjet; also true for the caller's own resource.
        if (!resourceRepository.isResourceVisibleToProjectManager(resourceId, currentUserId())) {
            throw new AccessDeniedException("Ressource hors périmètre");
        }
    }

    /**
     * Database id of the caller. Looked up rather than read from the token because the JWT
     * carries only the e-mail (ADR-017). Throws AccessDeniedException (403), not
     * NotFoundException, when no active account matches — a refusal, not a missing page.
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Null covers the same "no security context" case as hasFullAccess(); a null email
        // simply matches no row.
        String email = (auth != null) ? auth.getName() : null;
        return userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new AccessDeniedException("Utilisateur courant introuvable"))
                .getId();
    }
}

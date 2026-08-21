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

import java.util.List;

@Service
@RequiredArgsConstructor
public class ResourceService {

    /** Accès TCC complet : détenteurs de MANAGE_RESOURCES (Admin, Directeur). */
    private static final String FULL_ACCESS = "MANAGE_RESOURCES";

    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;
    private final ResourceMapper resourceMapper;
    private final TccAnnuelRepository tccAnnuelRepository;

    /**
     * Liste des ressources. Accès complet (Admin/Directeur : MANAGE_RESOURCES) → tout le référentiel ;
     * chef de projet (VIEW_RESOURCES seul) → uniquement les ressources de ses projets (scope PM, ADR-021).
     */
    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public List<ResourceResponse> findAll() {
        if (hasFullAccess()) {
            return resourceMapper.toResponseList(resourceRepository.findAllActive());
        }
        Long pmUserId = currentUserId();
        return resourceMapper.toResponseList(resourceRepository.findVisibleToProjectManager(pmUserId));
    }

    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public ResourceResponse findById(Long id) {
        Resource resource = loadResource(id);
        assertVisible(id);
        return resourceMapper.toResponse(resource);
    }

    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public ResourceResponse create(ResourceRequest request) {
        var user = userRepository.findById(request.userId())
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + request.userId()));

        if (resourceRepository.findActiveByUserId(request.userId()).isPresent()) {
            throw new IllegalArgumentException("Cet utilisateur est déjà une ressource active");
        }

        Resource resource = Resource.builder()
                .user(user)
                .dailyRate(request.dailyRate())
                .tccRate(request.tccRate())
                .staffingStart(request.staffingStart())
                .staffingEnd(request.staffingEnd())
                .build();

        return resourceMapper.toResponse(resourceRepository.save(resource));
    }

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

    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public void delete(Long id) {
        Resource resource = loadResource(id);
        resource.setDeleted(true);
        resourceRepository.save(resource);
    }

    // ── TCC par année (F-AFF-13 §6.3 règle 4) ─────────────────────

    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public List<TccAnnuelDto> findTccAnnuels(Long resourceId) {
        loadResource(resourceId);
        assertVisible(resourceId);
        return tccAnnuelRepository.findActiveByResourceId(resourceId).stream()
                .map(t -> new TccAnnuelDto(t.getAnnee(), t.getDailyRate(), t.getTccRate()))
                .toList();
    }

    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public List<TccAnnuelDto> replaceTccAnnuels(Long resourceId, List<TccAnnuelDto> rates) {
        Resource resource = loadResource(resourceId);

        long distinctYears = rates.stream().map(TccAnnuelDto::annee).distinct().count();
        if (distinctYears != rates.size()) {
            throw new IllegalArgumentException("Chaque année ne peut apparaître qu'une seule fois");
        }

        // Remplacement complet : soft-delete des entrées existantes puis insertion
        List<TccAnnuel> existing = tccAnnuelRepository.findActiveByResourceId(resourceId);
        existing.forEach(t -> t.setDeleted(true));
        tccAnnuelRepository.saveAll(existing);

        List<TccAnnuel> saved = tccAnnuelRepository.saveAll(rates.stream()
                .map(dto -> TccAnnuel.builder()
                        .resource(resource)
                        .annee(dto.annee())
                        .dailyRate(dto.dailyRate())
                        .tccRate(dto.tccRate())
                        .build())
                .toList());

        return saved.stream()
                .map(t -> new TccAnnuelDto(t.getAnnee(), t.getDailyRate(), t.getTccRate()))
                .toList();
    }

    private Resource loadResource(Long id) {
        return resourceRepository.findById(id)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new NotFoundException("Ressource introuvable : " + id));
    }

    // ── Scope de données PM (ADR-021) ─────────────────────────────

    /** Détenteur de MANAGE_RESOURCES → accès complet au référentiel TCC (Admin, Directeur). */
    private boolean hasFullAccess() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(FULL_ACCESS::equals);
    }

    /** Lève 403 si l'utilisateur n'a pas l'accès complet et que la ressource est hors de son périmètre PM. */
    private void assertVisible(Long resourceId) {
        if (hasFullAccess()) return;
        if (!resourceRepository.isResourceVisibleToProjectManager(resourceId, currentUserId())) {
            throw new AccessDeniedException("Ressource hors périmètre");
        }
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = (auth != null) ? auth.getName() : null;
        return userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new AccessDeniedException("Utilisateur courant introuvable"))
                .getId();
    }
}

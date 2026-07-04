package com.pms.project.service;

import com.pms.project.repository.ProjectRepository;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * Enforcement du SCOPE de données (ADR-021) : permission ∧ périmètre.
 *
 * <p>Le périmètre est dérivé de <b>capacités + relations</b>, jamais d'un nom de rôle (ADR-001) :
 * <ul>
 *   <li>capacité {@code VIEW_ALL_PROJECTS} → accès portefeuille complet (Directeur par défaut) ;</li>
 *   <li>relation chef de projet actif → projets où l'utilisateur est {@code chefProjet} ;</li>
 *   <li>relation d'affectation → projets où l'utilisateur est membre d'équipe actif.</li>
 * </ul>
 * Sans capacité globale ni relation : accès refusé (403).
 */
@Service
@RequiredArgsConstructor
public class ProjectScopeService {

    public static final String ALL_ACCESS = "VIEW_ALL_PROJECTS";

    private final ProjectRepository projectRepository;
    private final TeamAssignmentRepository teamAssignmentRepository;
    private final UserRepository userRepository;

    /** Vrai si l'utilisateur courant détient la capacité d'accès global (pas de filtre de périmètre). */
    public boolean hasAllAccess() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ALL_ACCESS::equals);
    }

    /** Identifiants des projets accessibles à l'utilisateur (chef de projet ∪ affectations actives). */
    @Transactional(readOnly = true)
    public Set<Long> accessibleProjectIds(String email) {
        Set<Long> ids = new HashSet<>();
        Long userId = userRepository.findActiveByEmailWithRole(email)
                .map(u -> u.getId())
                .orElse(null);
        if (userId == null) return ids;

        projectRepository.findActiveByChefProjetId(userId).forEach(p -> ids.add(p.getId()));
        teamAssignmentRepository.findActiveByUserId(userId).forEach(ta -> ids.add(ta.getProject().getId()));
        return ids;
    }

    /** Lève {@link AccessDeniedException} (→ 403) si le projet est hors périmètre de l'utilisateur. */
    @Transactional(readOnly = true)
    public void assertCanAccess(Long projectId, String email) {
        if (hasAllAccess()) return;
        if (!accessibleProjectIds(email).contains(projectId)) {
            throw new AccessDeniedException("Projet hors périmètre");
        }
    }
}

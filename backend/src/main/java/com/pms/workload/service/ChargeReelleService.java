package com.pms.workload.service;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import com.pms.workload.dto.ChargeReelleRequest;
import com.pms.workload.dto.ChargeReelleResponse;
import com.pms.workload.entity.ChargeReelle;
import com.pms.workload.mapper.ChargeReelleMapper;
import com.pms.workload.repository.ChargeReelleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChargeReelleService {

    private final ChargeReelleRepository chargeReelleRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ChargeReelleMapper chargeReelleMapper;
    private final TeamAssignmentRepository teamAssignmentRepository;

    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    @Transactional(readOnly = true)
    public List<ChargeReelleResponse> findByProject(Long projectId) {
        loadProject(projectId);
        if (canSeeAllWorkload()) {
            return chargeReelleMapper.toResponseList(chargeReelleRepository.findActiveByProjectId(projectId));
        }
        return chargeReelleMapper.toResponseList(
                chargeReelleRepository.findActiveByProjectIdAndUserId(projectId, currentUserId()));
    }

    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    @Transactional(readOnly = true)
    public Page<ChargeReelleResponse> findByProject(Long projectId, Pageable pageable) {
        loadProject(projectId);
        if (canSeeAllWorkload()) {
            return chargeReelleRepository.findActiveByProjectIdPaged(projectId, pageable)
                    .map(chargeReelleMapper::toResponse);
        }
        return chargeReelleRepository.findActiveByProjectIdAndUserIdPaged(projectId, currentUserId(), pageable)
                .map(chargeReelleMapper::toResponse);
    }

    @PreAuthorize("hasAuthority('SUBMIT_WORKLOAD')")
    @Transactional
    public ChargeReelleResponse submit(Long projectId, ChargeReelleRequest request) {
        Project project = loadProject(projectId);

        // BR-033 : seul un valideur (VALIDATE_WORKLOAD) peut soumettre pour un tiers.
        assertOwnership(request.userId());

        // H-8 : vérifier que l'utilisateur est membre actif de l'équipe du projet
        assertTeamMembership(project, request.userId());

        User user = loadUser(request.userId());
        LocalDate period = LocalDate.of(request.year(), request.month(), 1);

        if (chargeReelleRepository.existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(projectId, request.userId(), period)) {
            throw new IllegalArgumentException(
                    "Une charge réelle existe déjà pour cet utilisateur sur cette période");
        }

        ChargeReelle cr = ChargeReelle.builder()
                .project(project)
                .user(user)
                .period(period)
                .actualDays(request.actualDays())
                .submittedAt(LocalDateTime.now())
                .build();

        return chargeReelleMapper.toResponse(chargeReelleRepository.save(cr));
    }

    @PreAuthorize("hasAuthority('SUBMIT_WORKLOAD')")
    @Transactional
    public ChargeReelleResponse update(Long projectId, Long id, ChargeReelleRequest request) {
        ChargeReelle cr = loadChargeReelle(id);

        if (!cr.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge réelle introuvable : " + id);
        }

        if (cr.isValidated()) {
            throw new BusinessRuleException("Impossible de modifier une charge déjà validée");
        }

        // BR-033 : un développeur ne peut modifier que ses propres charges (même règle qu'à la soumission)
        assertOwnership(cr.getUser().getId());

        LocalDate expectedPeriod = LocalDate.of(request.year(), request.month(), 1);
        if (!cr.getPeriod().equals(expectedPeriod)) {
            throw new BusinessRuleException("La période d'une charge réelle ne peut pas être modifiée");
        }
        if (!cr.getUser().getId().equals(request.userId())) {
            throw new BusinessRuleException("L'utilisateur d'une charge réelle ne peut pas être modifié");
        }

        cr.setActualDays(request.actualDays());
        cr.setSubmittedAt(LocalDateTime.now());
        return chargeReelleMapper.toResponse(chargeReelleRepository.save(cr));
    }

    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public ChargeReelleResponse validate(Long projectId, Long id, String validatorEmail) {
        ChargeReelle cr = loadChargeReelle(id);

        if (!cr.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge réelle introuvable : " + id);
        }

        if (cr.isValidated()) {
            throw new BusinessRuleException("Cette charge est déjà validée");
        }

        User validator = userRepository.findActiveByEmailWithRole(validatorEmail)
                .orElseThrow(() -> new NotFoundException("Validateur introuvable"));

        cr.setValidatedAt(LocalDateTime.now());
        cr.setValidatedBy(validator);
        return chargeReelleMapper.toResponse(chargeReelleRepository.save(cr));
    }

    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public void delete(Long projectId, Long id) {
        ChargeReelle cr = loadChargeReelle(id);

        if (!cr.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge réelle introuvable : " + id);
        }

        if (cr.isValidated()) {
            throw new BusinessRuleException("Impossible de supprimer une charge déjà validée");
        }

        cr.setDeleted(true);
        chargeReelleRepository.save(cr);
    }

    private ChargeReelle loadChargeReelle(Long id) {
        return chargeReelleRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Charge réelle introuvable : " + id));
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }

    /** H-8 : l'utilisateur ciblé doit être membre actif de l'équipe (ou le chef de projet). */
    private void assertTeamMembership(Project project, Long targetUserId) {
        if (project.getChefProjet() != null && project.getChefProjet().getId().equals(targetUserId)) {
            return; // le chef de projet est implicitement membre
        }
        if (!teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(project.getId(), targetUserId)) {
            throw new BusinessRuleException(
                    "L'utilisateur n'est pas membre actif de l'équipe de ce projet");
        }
    }

    /**
     * BR-062…064 : la vue élargie (charges de toute l'équipe) est réservée aux porteurs
     * de VALIDATE_WORKLOAD (chef de projet, qui doit valider) ou VIEW_ALL_PROJECTS
     * (directeur, supervision portefeuille). Sinon → ses propres charges uniquement.
     * Check par capacité, jamais par nom de rôle (ADR-001).
     */
    private boolean canSeeAllWorkload() {
        return hasAuthority("VALIDATE_WORKLOAD") || hasAuthority("VIEW_ALL_PROJECTS");
    }

    /** Id de l'utilisateur courant ; -1 si introuvable (aucune charge ne remonte alors). */
    private Long currentUserId() {
        User current = currentUser();
        return current != null ? current.getId() : -1L;
    }

    /** BR-033 : un développeur (sans VALIDATE_WORKLOAD) ne peut agir que sur ses propres charges. */
    private void assertOwnership(Long targetUserId) {
        if (!hasAuthority("VALIDATE_WORKLOAD")) {
            User current = currentUser();
            if (current == null || !current.getId().equals(targetUserId)) {
                throw new AccessDeniedException("Un développeur ne peut agir que sur ses propres charges");
            }
        }
    }

    private boolean hasAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        return userRepository.findActiveByEmailWithRole(auth.getName()).orElse(null);
    }
}

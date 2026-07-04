package com.pms.workload.service;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
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

    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    @Transactional(readOnly = true)
    public List<ChargeReelleResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return chargeReelleMapper.toResponseList(chargeReelleRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('SUBMIT_WORKLOAD')")
    @Transactional
    public ChargeReelleResponse submit(Long projectId, ChargeReelleRequest request) {
        Project project = loadProject(projectId);

        // Un développeur ne peut déclarer QUE ses propres charges (BR-033) ; un valideur
        // (chef de projet / directeur, capacité VALIDATE_WORKLOAD) peut saisir pour un membre de l'équipe.
        if (!hasAuthority("VALIDATE_WORKLOAD")) {
            User current = currentUser();
            if (current == null || !current.getId().equals(request.userId())) {
                throw new AccessDeniedException("Un développeur ne peut soumettre que ses propres charges");
            }
        }

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
            throw new IllegalArgumentException("Impossible de modifier une charge déjà validée");
        }

        LocalDate expectedPeriod = LocalDate.of(request.year(), request.month(), 1);
        if (!cr.getPeriod().equals(expectedPeriod)) {
            throw new IllegalArgumentException("La période d'une charge réelle ne peut pas être modifiée");
        }
        if (!cr.getUser().getId().equals(request.userId())) {
            throw new IllegalArgumentException("L'utilisateur d'une charge réelle ne peut pas être modifié");
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
            throw new IllegalArgumentException("Cette charge est déjà validée");
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
            throw new IllegalArgumentException("Impossible de supprimer une charge déjà validée");
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

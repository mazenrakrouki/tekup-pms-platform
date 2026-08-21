package com.pms.workload;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import com.pms.workload.dto.ChargeReelleRequest;
import com.pms.workload.dto.ChargeReelleResponse;
import com.pms.workload.entity.ChargeReelle;
import com.pms.workload.mapper.ChargeReelleMapper;
import com.pms.workload.repository.ChargeReelleRepository;
import com.pms.workload.service.ChargeReelleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargeReelleServiceTest {

    @Mock ChargeReelleRepository chargeReelleRepository;
    @Mock ProjectRepository      projectRepository;
    @Mock UserRepository         userRepository;
    @Mock ChargeReelleMapper     chargeReelleMapper;
    @Mock TeamAssignmentRepository teamAssignmentRepository;

    @InjectMocks ChargeReelleService service;

    private Project project;
    private User developer;

    @BeforeEach
    void setUp() {
        project   = stubProject(1L);
        developer = stubUser(10L, "dev@pms.local");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── BR-033: submit ────────────────────────────────────────────

    @Test
    @DisplayName("BR-033 submit — un développeur peut soumettre sa propre charge")
    void submit_developerSubmitsOwnCharge_success() {
        // Pre-compute mocks before setting up stubs (avoids nested-stub Mockito issue)
        ChargeReelle savedCharge = stubCharge(10L, false);
        ChargeReelleResponse mockResponse = mock(ChargeReelleResponse.class);

        authenticateAs("dev@pms.local");                   // no VALIDATE_WORKLOAD
        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(userRepository.findActiveByEmailWithRole("dev@pms.local")).thenReturn(Optional.of(developer));
        when(teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(1L, 10L)).thenReturn(true);
        when(userRepository.findById(10L)).thenReturn(Optional.of(developer));
        when(chargeReelleRepository.existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(anyLong(), anyLong(), any()))
                .thenReturn(false);
        when(chargeReelleRepository.save(any())).thenReturn(savedCharge);
        when(chargeReelleMapper.toResponse(any())).thenReturn(mockResponse);

        // Should not throw
        service.submit(1L, new ChargeReelleRequest(10L, 2026, 6, BigDecimal.TEN));
    }

    @Test
    @DisplayName("BR-033 submit — un développeur ne peut pas soumettre la charge d'un autre")
    void submit_developerSubmitsForOther_throwsAccessDenied() {
        authenticateAs("dev@pms.local");                   // no VALIDATE_WORKLOAD
        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(userRepository.findActiveByEmailWithRole("dev@pms.local")).thenReturn(Optional.of(developer));

        // Request is for userId=99, but authenticated user is userId=10
        assertThatThrownBy(() -> service.submit(1L, new ChargeReelleRequest(99L, 2026, 6, BigDecimal.TEN)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("BR-033 submit — un validateur peut soumettre la charge d'un autre membre")
    void submit_validatorSubmitsForTeamMember_success() {
        ChargeReelle savedCharge = stubCharge(10L, false);
        ChargeReelleResponse mockResponse = mock(ChargeReelleResponse.class);

        authenticateAs("validator@pms.local", "VALIDATE_WORKLOAD");
        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(1L, 10L)).thenReturn(true);
        when(userRepository.findById(10L)).thenReturn(Optional.of(developer));
        when(chargeReelleRepository.existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(anyLong(), anyLong(), any()))
                .thenReturn(false);
        when(chargeReelleRepository.save(any())).thenReturn(savedCharge);
        when(chargeReelleMapper.toResponse(any())).thenReturn(mockResponse);

        // Should not throw
        service.submit(1L, new ChargeReelleRequest(10L, 2026, 6, BigDecimal.TEN));
    }

    @Test
    @DisplayName("H-8 submit — utilisateur non membre de l'équipe est rejeté")
    void submit_userNotTeamMember_throwsBusinessRule() {
        authenticateAs("validator@pms.local", "VALIDATE_WORKLOAD");
        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(1L, new ChargeReelleRequest(10L, 2026, 6, BigDecimal.TEN)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("membre actif");
    }

    @Test
    @DisplayName("submit — doublon sur même période est rejeté")
    void submit_duplicatePeriod_throwsIllegalArgument() {
        authenticateAs("validator@pms.local", "VALIDATE_WORKLOAD");
        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(1L, 10L)).thenReturn(true);
        when(userRepository.findById(10L)).thenReturn(Optional.of(developer));
        when(chargeReelleRepository.existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(
                eq(1L), eq(10L), eq(LocalDate.of(2026, 6, 1)))).thenReturn(true);

        assertThatThrownBy(() -> service.submit(1L, new ChargeReelleRequest(10L, 2026, 6, BigDecimal.TEN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existe déjà");
    }

    // ── BR-033: update ────────────────────────────────────────────

    @Test
    @DisplayName("update — impossible de modifier une charge validée")
    void update_validatedCharge_throwsBusinessRule() {
        authenticateAs("validator@pms.local", "VALIDATE_WORKLOAD");
        ChargeReelle validated = stubCharge(10L, true);
        when(chargeReelleRepository.findActiveById(42L)).thenReturn(Optional.of(validated));

        assertThatThrownBy(() ->
                service.update(1L, 42L, new ChargeReelleRequest(10L, 2026, 6, BigDecimal.TEN)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("validée");
    }

    @Test
    @DisplayName("BR-033 update — un développeur ne peut pas modifier la charge d'un autre")
    void update_developerModifiesOthersCharge_throwsAccessDenied() {
        authenticateAs("dev@pms.local");   // no VALIDATE_WORKLOAD
        // The charge belongs to userId=99, not developer (10)
        User otherUser = stubUser(99L, "other@pms.local");
        ChargeReelle cr = stubChargeForUser(otherUser, 1L, false);
        when(chargeReelleRepository.findActiveById(42L)).thenReturn(Optional.of(cr));
        when(userRepository.findActiveByEmailWithRole("dev@pms.local")).thenReturn(Optional.of(developer));

        assertThatThrownBy(() ->
                service.update(1L, 42L, new ChargeReelleRequest(99L, 2026, 6, BigDecimal.TEN)))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void authenticateAs(String email, String... authorities) {
        var auths = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, auths));
    }

    private Project stubProject(Long id) {
        Project p = mock(Project.class);
        when(p.getId()).thenReturn(id);
        when(p.getChefProjet()).thenReturn(null);
        return p;
    }

    private User stubUser(Long id, String email) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getEmail()).thenReturn(email);
        when(u.isDeleted()).thenReturn(false);
        return u;
    }

    private ChargeReelle stubCharge(Long userId, boolean validated) {
        return stubChargeForUser(stubUser(userId, "x@x.com"), 1L, validated);
    }

    private ChargeReelle stubChargeForUser(User user, Long projectId, boolean validated) {
        ChargeReelle cr = mock(ChargeReelle.class);
        Project p = mock(Project.class);
        when(p.getId()).thenReturn(projectId);
        when(cr.getProject()).thenReturn(p);
        when(cr.getUser()).thenReturn(user);
        when(cr.isValidated()).thenReturn(validated);
        when(cr.getPeriod()).thenReturn(LocalDate.of(2026, 6, 1));
        return cr;
    }
}

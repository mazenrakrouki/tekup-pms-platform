package com.pms.project;

import com.pms.project.repository.ProjectRepository;
import com.pms.project.service.ProjectScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectScopeServiceTest {

    @Mock ProjectRepository projectRepository;

    @InjectMocks ProjectScopeService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("hasAllAccess — retourne true quand VIEW_ALL_PROJECTS est présent")
    void hasAllAccess_withPermission_returnsTrue() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dir@pms.local", null,
                        List.of(new SimpleGrantedAuthority("VIEW_ALL_PROJECTS"))));

        assertThat(service.hasAllAccess()).isTrue();
    }

    @Test
    @DisplayName("hasAllAccess — retourne false sans la permission")
    void hasAllAccess_withoutPermission_returnsFalse() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm@pms.local", null,
                        List.of(new SimpleGrantedAuthority("VIEW_PROJECT"))));

        assertThat(service.hasAllAccess()).isFalse();
    }

    @Test
    @DisplayName("hasAllAccess — retourne false sans authentification")
    void hasAllAccess_noAuth_returnsFalse() {
        assertThat(service.hasAllAccess()).isFalse();
    }

    @Test
    @DisplayName("accessibleProjectIds — email null retourne ensemble vide")
    void accessibleProjectIds_nullEmail_returnsEmpty() {
        assertThat(service.accessibleProjectIds(null)).isEmpty();
        verifyNoInteractions(projectRepository);
    }

    @Test
    @DisplayName("accessibleProjectIds — délègue à la requête unique du repository")
    void accessibleProjectIds_delegatesToRepository() {
        Set<Long> expected = Set.of(1L, 2L, 5L);
        when(projectRepository.findAccessibleProjectIdsByEmail("pm@pms.local")).thenReturn(expected);

        assertThat(service.accessibleProjectIds("pm@pms.local")).isEqualTo(expected);
    }

    @Test
    @DisplayName("assertCanAccess — laisse passer un utilisateur avec VIEW_ALL_PROJECTS")
    void assertCanAccess_globalAccess_doesNotThrow() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dir@pms.local", null,
                        List.of(new SimpleGrantedAuthority("VIEW_ALL_PROJECTS"))));

        // Should not throw — global access bypasses the scope check
        service.assertCanAccess(99L, "dir@pms.local");
        verifyNoInteractions(projectRepository);
    }

    @Test
    @DisplayName("assertCanAccess — lève AccessDeniedException si projet hors périmètre")
    void assertCanAccess_projectOutOfScope_throwsAccessDenied() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm@pms.local", null,
                        List.of(new SimpleGrantedAuthority("VIEW_PROJECT"))));

        when(projectRepository.findAccessibleProjectIdsByEmail("pm@pms.local"))
                .thenReturn(Set.of(1L, 2L));

        assertThatThrownBy(() -> service.assertCanAccess(99L, "pm@pms.local"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("périmètre");
    }

    @Test
    @DisplayName("assertCanAccess — laisse passer si projet dans le périmètre")
    void assertCanAccess_projectInScope_doesNotThrow() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm@pms.local", null,
                        List.of(new SimpleGrantedAuthority("VIEW_PROJECT"))));

        when(projectRepository.findAccessibleProjectIdsByEmail("pm@pms.local"))
                .thenReturn(Set.of(1L, 2L, 3L));

        service.assertCanAccess(2L, "pm@pms.local"); // should not throw
    }
}

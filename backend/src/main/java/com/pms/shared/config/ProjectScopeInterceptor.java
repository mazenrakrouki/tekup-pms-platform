package com.pms.shared.config;

import com.pms.project.service.ProjectScopeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforcement systématique du scope (ADR-021) sur toutes les ressources rattachées à un projet
 * ({@code /api/projects/{id}/**}). Centralise la vérification de périmètre pour ne pas la disperser
 * dans chaque service. Lève {@link org.springframework.security.access.AccessDeniedException} (→ 403)
 * si le projet ciblé est hors périmètre de l'utilisateur courant.
 */
@Component
@RequiredArgsConstructor
public class ProjectScopeInterceptor implements HandlerInterceptor {

    // Capture l'id de projet dans /api/projects/{id} et /api/projects/{id}/...
    private static final Pattern PROJECT_PATH = Pattern.compile("^/api/projects/(\\d+)(/.*)?$");

    private final ProjectScopeService scopeService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Matcher m = PROJECT_PATH.matcher(request.getRequestURI());
        if (!m.matches()) {
            return true; // pas une ressource scopée par projet
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return true; // l'absence d'authentification est gérée en amont (401)
        }

        Long projectId = Long.valueOf(m.group(1));
        scopeService.assertCanAccess(projectId, auth.getName()); // → 403 si hors périmètre
        return true;
    }
}

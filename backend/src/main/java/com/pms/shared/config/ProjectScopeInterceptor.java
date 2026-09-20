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

// Reads the project id out of the URL and refuses the request when that project is outside
// the caller's perimeter (ADR-021, the systematic half). Without it a project manager could
// edit the id in the address bar and read another project's budget and margins (IDOR).
// Covers only URLs carrying a project number; list endpoints are filtered by ProjectService
// itself via ProjectScopeService.

/**
 * Enforces project scope (ADR-021) on every {@code /api/projects/{id}/**} resource, in one
 * place instead of scattered through each service. Throws {@link org.springframework.security.access.AccessDeniedException}
 * (→ 403) when the project is outside the current user's perimeter. A {@code HandlerInterceptor}
 * rather than a servlet filter because it needs to run after Spring Security has identified the caller.
 */
@Component
@RequiredArgsConstructor
public class ProjectScopeInterceptor implements HandlerInterceptor {

    // Matches /api/projects/{digits} and /api/projects/{digits}/anything, capturing the id.
    // static final: Pattern is expensive to compile and this runs on every project request.
    private static final Pattern PROJECT_PATH = Pattern.compile("^/api/projects/(\\d+)(/.*)?$");

    private final ProjectScopeService scopeService;

    /**
     * Runs before the controller method; lets the request continue on success. On an
     * out-of-scope project it never returns {@code false} (a silent empty 200) — it lets
     * {@code assertCanAccess} throw, which GlobalExceptionHandler turns into a real 403.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Matcher m = PROJECT_PATH.matcher(request.getRequestURI());
        if (!m.matches()) {
            // No project id in the URL (e.g. GET/POST /api/projects) - nothing to check here.
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // Missing auth is handled earlier (401); avoids a confusing 403 or NPE here.
            return true;
        }

        Long projectId = Long.valueOf(m.group(1));
        scopeService.assertCanAccess(projectId, auth.getName()); // throws → 403 when out of perimeter
        return true;
    }
}

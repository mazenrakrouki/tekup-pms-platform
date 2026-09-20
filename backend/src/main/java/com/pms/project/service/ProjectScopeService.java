package com.pms.project.service;

import com.pms.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/*
 * Answers "which projects may this request's caller touch?" — the scope half of ADR-021,
 * separate from permission (hasAuthority checks answer "may they act on projects at all?").
 * Called by ProjectScopeInterceptor before every /api/projects/{id}/** request, and by
 * ProjectService to filter list endpoints (which carry no id for the interceptor to check).
 *
 * Note: findAccessibleProjectIdsByEmail filters out archived projects, so a caller without
 * VIEW_ALL_PROJECTS gets 403 on unarchiving a project they themselves just archived - a side
 * effect of that filter, not an intentional rule.
 *
 * Scope is built from a capability (VIEW_ALL_PROJECTS) plus real DB relationships (chef de
 * projet, active team member) rather than a role name, per ADR-001: permissions are rows an
 * admin edits at runtime, not strings baked into code.
 */
@Service
@RequiredArgsConstructor
public class ProjectScopeService {

    // Same text as a row in the "permissions" table; a typo here would silently break
    // hasAllAccess() with no error anywhere, hence one named constant instead of copies.
    public static final String ALL_ACCESS = "VIEW_ALL_PROJECTS";

    private final ProjectRepository projectRepository;

    /** Whether the current request's caller holds VIEW_ALL_PROJECTS (false if nobody is logged in). */
    public boolean hasAllAccess() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ALL_ACCESS::equals);
    }

    /**
     * Ids of every project this e-mail may work on (as chef de projet or active team member).
     * One EXISTS-based query (see ProjectRepository) instead of three round trips per request.
     */
    @Transactional(readOnly = true)
    public Set<Long> accessibleProjectIds(String email) {
        if (email == null) return Set.of();
        return projectRepository.findAccessibleProjectIdsByEmail(email);
    }

    /**
     * Guard used before acting on one project: returns quietly if in scope, throws
     * AccessDeniedException (→ 403) otherwise. Throws rather than returning a boolean so the
     * check can't be silently ignored by a caller who forgets to test it. Re-checks the id even
     * though it comes from the URL, closing an IDOR: editing /api/projects/4 to .../9 by hand
     * must not open project 9.
     */
    @Transactional(readOnly = true)
    public void assertCanAccess(Long projectId, String email) {
        if (hasAllAccess()) return;
        if (!accessibleProjectIds(email).contains(projectId)) {
            throw new AccessDeniedException("Projet hors périmètre");
        }
    }
}

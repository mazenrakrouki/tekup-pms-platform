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

// =============================================================================
// FILE: ProjectScopeInterceptor.java
//
// WHAT THIS FILE IS
//   The guard that reads the project number out of the URL and refuses the
//   request when that project is outside the perimeter of the person asking.
//   It is the systematic half of ADR-021.
//
// WHERE IT SITS IN THE FLOW
//   Angular client
//     -> Spring Security chain (SecurityConfig, same package): the JWT filter
//        installs the caller, and the rule anyRequest().authenticated() has
//        already answered 401 for anybody unknown;
//     -> Spring MVC picks the controller method;
//     -> THIS FILE runs, because WebMvcConfig (same package) registers it on
//        "/api/projects/**". It runs BEFORE the controller method starts;
//     -> ProjectScopeService.assertCanAccess(projectId, email) does the real
//        work: it compares the project with the ids the person may work on;
//     -> controller -> service, where @PreAuthorize("hasAuthority('X')") is
//        evaluated.
//   It calls one method of one class. It reads no table itself.
//
// WHY IT EXISTS - the sentence to say in front of a jury
//   Holding the permission is not enough. VIEW_DEVIS_INTERNE means "this person
//   may look at an internal quote"; it does not say WHICH one. Two project
//   managers both hold that permission, and each must see only his own
//   projects. Permission answers "may he do it?", scope answers "on which
//   data?" - ADR-021 is the decision to enforce both, always.
//   The check could have been written inside every service method, and that is
//   exactly what this file avoids: about a dozen controllers hang under
//   /api/projects/{projectId}/... (risks, livrables, missions, sprints,
//   backlog, kpi, devis-interne, parties-prenantes, demandes-changement,
//   avenants...). One forgotten check in one of them is a leak, and the leak is
//   silent - nothing fails, the wrong data is simply served. Here the rule is
//   written once, so a controller added tomorrow under that prefix is covered
//   on the day it is written, with no extra line of code.
//
// THE ATTACK IT CLOSES
//   The project id comes from the address bar, so the browser can change it by
//   hand. Without this class, a project manager who is allowed on project 4
//   types /api/projects/9/devis-interne and reads the budget, the cost prices
//   and the margin of a project that belongs to a colleague. The weakness has a
//   name: IDOR, Insecure Direct Object Reference.
//
// WHAT IT DOES NOT COVER - say this before somebody else does
//   Only URLs that carry a project number. The list endpoints (GET
//   /api/projects) have no id in the address, so they cannot be filtered here;
//   ProjectService calls ProjectScopeService itself for those. And an
//   interceptor is Spring MVC, not Spring Security: it runs after the whole
//   security chain, so it never replaces authentication - it adds one check on
//   top of it.
// =============================================================================

/**
 * Systematic enforcement of the scope (ADR-021) on every resource attached to a
 * project ({@code /api/projects/{id}/**}). It keeps the perimeter check in one
 * place instead of scattering it through every service. It throws
 * {@link org.springframework.security.access.AccessDeniedException} (→ 403)
 * when the project aimed at is outside the perimeter of the current user.
 *
 * <p>Why a {@code HandlerInterceptor} and not a servlet filter: an interceptor
 * runs inside Spring MVC, after the security chain has identified the caller,
 * and it can be registered on a URL pattern from WebMvcConfig. A filter would
 * run earlier, on every request including the static files, and would have to
 * parse the URL for itself without knowing whether any controller handles it.
 */
@Component
@RequiredArgsConstructor
public class ProjectScopeInterceptor implements HandlerInterceptor {

    // Reads the project id out of /api/projects/{id} and /api/projects/{id}/...
    // Piece by piece:
    //   ^                 the address must START here, so ".../api/projects/9"
    //                     hidden inside a longer path cannot match by accident;
    //   /api/projects/    the exact prefix;
    //   (\\d+)            group 1: one or more digits - the project id, read
    //                     back below with m.group(1). Only digits, so a text id
    //                     simply does not match and the request goes through
    //                     untouched (Spring MVC will answer 400 by itself);
    //   (/.*)?            an optional tail: everything that may follow, such as
    //                     "/devis-interne" or "/sprints/12". The "?" is what
    //                     makes /api/projects/7 match as well as
    //                     /api/projects/7/risks;
    //   $                 and the address must END there.
    // In Java source, "\\d" is how one writes the regular-expression "\d".
    // static final and compiled once: a Pattern is expensive to build and this
    // code runs on every project request. Compiling it inside the method below
    // would rebuild the same object thousands of times a day.
    private static final Pattern PROJECT_PATH = Pattern.compile("^/api/projects/(\\d+)(/.*)?$");

    // The class that owns the rule "which projects may this person touch?".
    // Lombok's @RequiredArgsConstructor writes the constructor, and Spring
    // passes the service in. Keeping the rule there, not here, means the same
    // answer is used by this interceptor and by ProjectService.
    private final ProjectScopeService scopeService;

    /**
     * Runs before the controller method. Returns {@code true} to let the
     * request continue, and never returns {@code false}: when the project is
     * out of reach it does not return at all, it lets an exception through.
     *
     * <p>Why an exception rather than {@code return false}: returning false
     * stops the request silently with an empty 200, which the Angular client
     * would read as "everything is fine, there is just no data". The
     * AccessDeniedException thrown by the service is caught by
     * GlobalExceptionHandler and turned into a real 403 with the body
     * "Accès refusé", which the client knows how to show.
     *
     * <p>{@code handler} is the controller method Spring MVC has chosen. It is
     * not used here on purpose: the rule depends on the URL and on the caller,
     * not on which Java method is about to run.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // getRequestURI() gives the path only, without the query string, for
        // example "/api/projects/7/risks". Matching on the query string too
        // would make "?name=/api/projects/7" look like a project URL.
        Matcher m = PROJECT_PATH.matcher(request.getRequestURI());
        if (!m.matches()) {
            // Not a project-scoped resource. WebMvcConfig registers this
            // interceptor on "/api/projects/**", so this branch catches the
            // addresses that match that pattern but carry no number, such as
            // GET /api/projects (the list) or POST /api/projects (creation).
            // A list has no single project to check; ProjectService filters it
            // with the same ProjectScopeService instead.
            return true;
        }

        // Who is calling, as JwtAuthenticationFilter wrote it into the security
        // context earlier in the chain.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // Missing authentication is handled earlier (401). Reaching this
            // line means the request is on a path Spring Security lets through,
            // so there is no user to check a perimeter against. Throwing here
            // would turn a clean 401 into a confusing 403, and a
            // NullPointerException on auth.getName() below would turn it into a
            // 500.
            return true;
        }

        // group(1) is the digits captured by the regular expression above, so
        // the text is known to be a number. auth.getName() is the e-mail of the
        // logged-in user - the same key ProjectScopeService uses in its query.
        Long projectId = Long.valueOf(m.group(1));
        // The real decision, and it is deliberately NOT taken here: the service
        // returns quietly when the project is inside the perimeter, and throws
        // AccessDeniedException (→ 403) when it is not. A caller holding
        // VIEW_ALL_PROJECTS, typically the director, passes straight through
        // with no database read at all.
        scopeService.assertCanAccess(projectId, auth.getName()); // → 403 when out of perimeter
        return true;
    }
}

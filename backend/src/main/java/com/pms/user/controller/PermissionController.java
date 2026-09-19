package com.pms.user.controller;

import com.pms.user.dto.PermissionResponse;
import com.pms.user.dto.PermissionWithRolesResponse;
import com.pms.user.service.PermissionAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// ============================================================================
// FILE: PermissionController
//
// WHAT THIS FILE IS
//   The HTTP door to the permission catalogue. It publishes one single
//   read-only endpoint, GET /api/admin/permissions, which lists every
//   permission code the application knows about.
//
// WHERE IT SITS IN THE FLOW
//   Angular RbacService.listPermissions() and listPermissionsWithRoles()
//   (core/services/rbac.service.ts), used by the admin permission screen and by
//   the dashboard counter
//     -> SecurityConfig, where anyRequest().authenticated() already rejects a
//        request without a valid JWT (401, before this class is even reached)
//     -> THIS FILE: it reads one query parameter and picks which service call
//        to make. It decides nothing else.
//     -> PermissionAdminService, guarded by
//        @PreAuthorize("hasAuthority('MANAGE_ROLES')")
//     -> PermissionRepository and RoleRepository -> tables permissions, roles
//        and the join table role_permissions
//     -> back out as PermissionResponse or PermissionWithRolesResponse, turned
//        into JSON.
//
// WHY IT EXISTS
//   Delete it and the admin screens go blind. The role editor needs the list of
//   permissions to draw its checkboxes, and the permission screen needs it to
//   answer "who is allowed to do this?". The codes live in the Java source
//   (@PreAuthorize("hasAuthority('X')")) and in the Flyway migrations, so the
//   browser has no other way to discover them.
//
// WHY THERE IS NO POST, PUT OR DELETE HERE - EXPECT THIS QUESTION
//   A permission only has an effect if some Java code names it. Creating the
//   code "FOO" from a screen would store a row that guards nothing, and
//   deleting "MANAGE_DI" would silently close a feature for everybody. So the
//   catalogue is owned by the code and by the migrations (V2 seeds it, V20
//   removed MANAGE_ROLES, V25 put it back when these screens were built), and
//   administering RBAC means ATTACHING permissions TO ROLES - which is
//   RoleController's job. The missing CRUD is a deliberate decision, not a gap.
//
// WHY THE PERMISSION CHECK IS NOT WRITTEN IN THIS FILE
//   @PreAuthorize sits on the SERVICE methods, never on the controller. The
//   guard has to travel with the business method: the day another service, a
//   batch job or a test calls PermissionAdminService directly, the check still
//   runs. A check written on the URL only protects that one URL.
// ============================================================================

// @Tag is documentation only (springdoc / OpenAPI). It groups both endpoints of
// this class under one heading in the Swagger page served at /swagger-ui.html.
// Without it the generated page would file them under "permission-controller",
// the raw class name. The text stays in French because the whole interface and
// the rest of the API documentation are in French.
//
// @RestController = @Controller + @ResponseBody. It tells Spring to create one
// instance of this class at start-up and to scan it for URL mappings, and it
// says that what a method returns IS the response body (Jackson turns it into
// JSON). Without @ResponseBody built in, the returned value would be read as
// the name of an HTML view to render, and the client would get a 404 on a view
// called something like "java.util.ArrayList".
//
// @RequestMapping sets the common prefix once. Every mapping below is relative
// to /api/admin/permissions. Without it each method would have to repeat the
// full path, and a typo in one of them would silently publish an endpoint the
// Angular service never calls.
//
// @RequiredArgsConstructor is Lombok: it writes a constructor taking every
// final field, and Spring uses that single constructor to inject the service
// (constructor injection). Why not @Autowired on the field: a final field set
// through the constructor can never be null and can never be swapped at
// runtime, and a plain "new PermissionController(service)" works in a unit test
// without any Spring machinery.
/**
 * Read-only HTTP endpoint for the RBAC permission catalogue (ADR-001).
 *
 * <p>The class holds no logic on purpose: it reads one query parameter, calls
 * the service, and returns what it gets. Every decision that can be made
 * elsewhere - which rows, in which order, who may see them - belongs to the
 * service, so it stays true whatever calls it.
 */
@Tag(name = "Permissions", description = "Consultation du référentiel de permissions RBAC")
@RestController
@RequestMapping("/api/admin/permissions")
@RequiredArgsConstructor
public class PermissionController {

    // The only collaborator. It is final, so Lombok puts it in the generated
    // constructor and Spring hands over the singleton service at start-up.
    private final PermissionAdminService permissionAdminService;

    // WHAT IT DOES
    //   Answers GET /api/admin/permissions with the whole permission catalogue,
    //   sorted by module then by code (the sorting is done in the service).
    //   With ?withRoles=true it answers with the same rows, each one carrying
    //   the names of the roles that currently hold it.
    //
    // WHAT IT GIVES BACK
    //   200 with a JSON array. Either List<PermissionResponse> (id, code,
    //   module, description) or List<PermissionWithRolesResponse> (the same
    //   four fields plus roleNames). 401 if the caller has no valid token,
    //   403 if the caller does not hold MANAGE_ROLES - that 403 is raised
    //   inside the service by @PreAuthorize and turned into a clean response by
    //   GlobalExceptionHandler.
    //
    // WHY ONE ENDPOINT WITH A FLAG RATHER THAN TWO SEPARATE URLS
    //   The two answers describe the same thing at two levels of detail, and
    //   the expensive one costs a pass over every role. A flag lets the cheap
    //   answer stay cheap for the role editor, which asks for the catalogue
    //   every time it opens, while the audit screen pays for the extra work
    //   only when it needs it. Two URLs would duplicate the mapping, the
    //   security note and the Swagger entry for one boolean.
    //
    // @GetMapping with no path = the class prefix itself,
    // GET /api/admin/permissions. GET and not POST because this only reads:
    // a GET can be cached, retried and bookmarked safely.
    //
    // @RequestParam binds the ?withRoles=... piece of the URL to the argument.
    //   name = "withRoles"   -> the exact spelling expected in the query string
    //   defaultValue="false" -> what to use when the parameter is absent
    // Why defaultValue matters: without it, and because the argument is the
    // primitive boolean, a plain call to /api/admin/permissions would have
    // nothing to bind and Spring would answer 500 trying to put null into a
    // boolean. With it, the plain call means "the cheap list", which is exactly
    // what RbacService.listPermissions() expects.
    // Note that Spring accepts "true" and "false" here; a value such as
    // ?withRoles=yes is a binding failure, answered 400 through
    // MethodArgumentTypeMismatchException in GlobalExceptionHandler.
    //
    // WHY THE RETURN TYPE IS ResponseEntity<?>
    //   The two branches return two different list types, and Java needs one
    //   single declared type for the method. The wildcard <?> means "some type
    //   I am not naming here". The client is not affected - Jackson serialises
    //   the real object, so the JSON is exactly the same as with a precise
    //   type. The cost is that the OpenAPI page cannot show the exact schema
    //   for this operation; that is the trade accepted for the single endpoint.
    /**
     * Lists the permissions. {@code ?withRoles=true} adds to each permission
     * the list of the roles that hold it.
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(name = "withRoles", defaultValue = "false") boolean withRoles) {
        // The expensive branch: findAllWithRoles() loads every live role once,
        // then for each permission keeps the names of the roles whose
        // permission set contains it. This reverse direction
        // (permission -> roles) is not a field on the Permission entity,
        // because role_permissions is a many-to-many table read from the role
        // side, so it has to be computed.
        if (withRoles) {
            // The local variable is typed on purpose. Without it, the compiler
            // would have to guess a common type for both branches, and a
            // reader could no longer tell from this file which record each
            // branch actually sends.
            List<PermissionWithRolesResponse> body = permissionAdminService.findAllWithRoles();
            // ResponseEntity.ok(...) = status 200 plus this object as the body.
            // Building the response explicitly, rather than just returning the
            // list, keeps every method of the project consistent: the ones that
            // must answer 201 or 204 have no other choice, so they all read the
            // same way.
            return ResponseEntity.ok(body);
        }
        // The cheap branch: the four flat fields, nothing computed. This is
        // what the role editor asks for each time it opens.
        List<PermissionResponse> body = permissionAdminService.findAll();
        return ResponseEntity.ok(body);
    }
}

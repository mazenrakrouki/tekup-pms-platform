package com.pms.user.controller;

import com.pms.user.dto.RoleRequest;
import com.pms.user.dto.RoleResponse;
import com.pms.user.service.RoleAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

// ============================================================================
// FILE: RoleController
//
// WHAT THIS FILE IS
//   The HTTP door to role administration: the five endpoints under
//   /api/admin/roles that list, read, create, modify and delete a role, and
//   above all that decide which permissions each role carries.
//
// WHERE IT SITS IN THE FLOW
//   Angular RbacService (core/services/rbac.service.ts), used by the admin
//   role screen
//     -> SecurityConfig: anyRequest().authenticated(), so no token means 401
//        before this class is reached
//     -> THIS FILE: it maps a URL and an HTTP verb onto a service call, asks
//        Bean Validation to check the body, and chooses the status code
//     -> RoleAdminService, every method guarded by
//        @PreAuthorize("hasAuthority('MANAGE_ROLES')")
//     -> RoleRepository, PermissionRepository, UserRepository -> tables roles,
//        permissions, role_permissions, users
//     -> back out as RoleResponse (with its permissions nested inside).
//
// WHY IT EXISTS - AND WHY IT IS THE MOST SENSITIVE ADMIN SCREEN
//   This is where the authorization matrix is actually edited. ADR-001 says
//   authorization is dynamic and permission-based: no Java code tests a role
//   NAME, every check tests a permission code. So giving a new right to all
//   project managers is one row added in role_permissions through PUT
//   /api/admin/roles/{id} - no recompile, no redeploy. Delete this controller
//   and the matrix becomes editable only by writing SQL by hand.
//   The flip side is that a mistake here changes what everybody may do, which
//   is why RoleAdminService refuses to rename or delete a built-in role, and
//   refuses to delete a role that users still carry.
//
// WHAT HAPPENS AFTER A WRITE - THE PART THAT IS NOT IN THIS FILE
//   RoleAdminService.update() and delete() clear the "securityContext" cache
//   (ADR-017). The JWT filter keeps the authority list of each signed-in user
//   in that cache, so without the clear an administrator could remove a
//   permission and the people already signed in would keep using it until
//   their token expired.
//
// WHY THE PERMISSION CHECK IS NOT WRITTEN IN THIS FILE
//   @PreAuthorize("hasAuthority('MANAGE_ROLES')") sits on the SERVICE methods.
//   The guard belongs to the operation, not to the URL: any future caller of
//   RoleAdminService - another service, a scheduled job, a test - meets the
//   same check. MANAGE_ROLES itself was removed by migration V20, when these
//   screens did not exist, and restored by V25 which also grants it to ADMIN.
//
// ADR-021 DOES NOT APPLY HERE
//   ProjectScopeInterceptor only inspects URLs that match
//   /api/projects/{id}/**, so it never looks at these paths. That is correct:
//   a role belongs to the whole application, not to one project, so there is
//   no project scope to enforce. Say it that way if asked - it is a scope that
//   does not exist, not a check that was forgotten.
// ============================================================================

// @Tag: springdoc/OpenAPI only. It groups the five endpoints under the heading
// "Rôles" in the Swagger page instead of the raw class name.
//
// @RestController: Spring creates one instance at start-up, scans it for URL
// mappings, and treats whatever a method returns as the response body, which
// Jackson writes as JSON. Without it the returned value would be taken for the
// name of an HTML view.
//
// @RequestMapping: the shared prefix, written once. The five mappings below are
// relative to /api/admin/roles. The "/admin/" segment is a naming convention
// for the reader; it grants nothing by itself - MANAGE_ROLES on the service is
// what actually protects these routes.
//
// @RequiredArgsConstructor: Lombok writes the constructor that takes every
// final field, and Spring injects through it. A final field set in the
// constructor cannot be null and cannot be replaced at runtime, and the class
// can be built by hand in a test with no Spring context at all.
/**
 * Administration of roles and of the permissions attached to them (ADR-001).
 *
 * <p>The class deliberately contains no rule: it maps HTTP onto
 * {@link RoleAdminService} and picks the status code. Every rule that protects
 * the matrix - built-in roles cannot be renamed or deleted, a role still
 * carried by a user cannot be deleted, the security cache must be cleared after
 * a change - lives in the service, where it applies to every caller and not
 * only to these five URLs.
 */
@Tag(name = "Rôles", description = "Administration des rôles et affectation des permissions (RBAC dynamique)")
@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class RoleController {

    // The only collaborator. Final, so Lombok puts it in the generated
    // constructor and Spring supplies the singleton service at start-up.
    private final RoleAdminService roleAdminService;

    // WHAT IT DOES / GIVES BACK
    //   GET /api/admin/roles -> 200 with every live role, sorted by name, each
    //   one carrying its description, whether it is a built-in role, how many
    //   live users hold it, and the full list of its permissions.
    // WHY the userCount travels with the role rather than being fetched screen
    //   by screen: the admin list uses it to grey out the delete button, and
    //   the service uses the same number to refuse the delete. One source, so
    //   the button and the server can never disagree.
    // 403 if the caller lacks MANAGE_ROLES - raised by @PreAuthorize inside the
    //   service and turned into a clean body by GlobalExceptionHandler.
    //
    // @GetMapping with no path means the class prefix itself.
    // The generic type List<RoleResponse> is not decoration: it is what tells
    // Jackson, and the OpenAPI generator, the exact shape of the array. Without
    // it the Swagger page would advertise an untyped array and the Angular side
    // would have nothing to check its Role interface against.
    @GetMapping
    public ResponseEntity<List<RoleResponse>> list() {
        return ResponseEntity.ok(roleAdminService.findAll());
    }

    // WHAT IT DOES / GIVES BACK
    //   GET /api/admin/roles/{id} -> 200 with one role, 404 if the id does not
    //   exist or points at a row flagged deleted (the service raises
    //   NotFoundException, which GlobalExceptionHandler maps to 404).
    //
    // @PathVariable takes the {id} piece of the URL and binds it to the
    // argument. The name matches because the code is compiled with parameter
    // names kept (spring-boot-maven-plugin does this by default); the day that
    // changed, the explicit form @PathVariable("id") would be required.
    // Typing it as Long, not String, makes Spring convert it before the method
    // runs: /api/admin/roles/abc never reaches this body, it is answered 400
    // through MethodArgumentTypeMismatchException. Without that typing the
    // conversion would happen deeper down and surface as a 500.
    @GetMapping("/{id}")
    public ResponseEntity<RoleResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(roleAdminService.findById(id));
    }

    // WHAT IT DOES / GIVES BACK
    //   POST /api/admin/roles creates a role from { name, description,
    //   permissionIds } and answers 201 Created with the saved role, plus a
    //   Location header pointing at the new row.
    //   409 if the name is already taken (IllegalArgumentException is mapped to
    //   Conflict for the whole project), 400 if the body breaks a rule of
    //   RoleRequest, 404 if one of the permission ids does not exist.
    //   The new role is always created with system = false: a built-in role can
    //   only come from a migration, never from this endpoint.
    //
    // @PostMapping, not @PutMapping: the server chooses the id, so sending the
    // same body twice creates two roles. That is exactly what POST means, and
    // it is why PUT below needs an id in the URL.
    //
    // @RequestBody tells Spring to read the HTTP body and let Jackson build a
    // RoleRequest out of the JSON. Without it Spring would look for the fields
    // in the query string and hand the method an object with everything null.
    //
    // @Valid switches on Bean Validation for that object BEFORE this method
    // runs: the @NotBlank, @Size and @Pattern rules written on RoleRequest are
    // checked, and a failure throws MethodArgumentNotValidException, which
    // GlobalExceptionHandler turns into a 400 naming each bad field.
    // Concrete example of what it prevents: without @Valid a POST with
    // "name": "chef projet" would reach the service, be trimmed, saved, and the
    // seeders that look roles up by the exact string "CHEF_PROJET" would never
    // match it again.
    @PostMapping
    public ResponseEntity<RoleResponse> create(@Valid @RequestBody RoleRequest request) {
        RoleResponse created = roleAdminService.create(request);
        // Builds the absolute address of the row that was just created.
        // fromCurrentRequest() starts from the URL of THIS request
        // (http://host/api/admin/roles), .path("/{id}") adds the placeholder,
        // and buildAndExpand(created.id()) fills it in - so the result is
        // http://host/api/admin/roles/42.
        // WHY not build the string by hand: fromCurrentRequest() keeps the
        // scheme, host and port the client actually used. Behind the Docker
        // reverse proxy a hand-written "http://localhost:8080/..." would send
        // the browser to an address that does not exist for it.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // 201 Created + Location header: the HTTP way of saying "made it, and
        // here is where it now lives". The body is sent as well so the Angular
        // screen can insert the row without a second round trip - it needs the
        // generated id and the resolved permission list.
        return ResponseEntity.created(location).body(created);
    }

    // WHAT IT DOES / GIVES BACK
    //   PUT /api/admin/roles/{id} -> 200 with the updated role.
    //   422 if the role is a built-in one and the name is being changed
    //   (BusinessRuleException), 409 if another role already uses the new name,
    //   404 if the role or one of the permission ids is unknown, 400 if the
    //   body breaks a validation rule.
    //
    // WHY PUT AND NOT PATCH - AND WHY THIS IS THE DANGEROUS ENDPOINT
    //   PUT means "make the role look exactly like this body". The service does
    //   role.setPermissions(...), which REPLACES the whole set. Sending an
    //   empty permissionIds therefore strips every permission from the role.
    //   The Angular editor always posts the full list of ticked boxes, which is
    //   what makes it safe. A hand-written call that sent only the name would
    //   leave the role with no permission at all, and everybody holding it
    //   would start getting 403 everywhere.
    //
    // The two arguments read from two different places: {id} comes from the
    // URL through @PathVariable, the rest comes from the JSON body through
    // @RequestBody. The id is deliberately NOT in the body - that way there is
    // only one id in play and no way for the two to disagree.
    @PutMapping("/{id}")
    public ResponseEntity<RoleResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody RoleRequest request) {
        return ResponseEntity.ok(roleAdminService.update(id, request));
    }

    // WHAT IT DOES / GIVES BACK
    //   DELETE /api/admin/roles/{id} -> 204 No Content on success.
    //   422 if the role is built-in, or if at least one live user still holds
    //   it (the message says how many, so the administrator knows what to
    //   reassign first). 404 if the id is unknown.
    //
    // ROLES ARE REALLY DELETED, NOT SOFT-DELETED
    //   Almost every table here keeps the row and raises a "deleted" flag. The
    //   service calls roleRepository.delete(role), which removes the row for
    //   good; the matching lines of role_permissions disappear with it through
    //   ON DELETE CASCADE (V1). That is only safe because of the two refusals
    //   above: users.role_id is NOT NULL, so deleting a role that people still
    //   carry would leave accounts pointing at nothing, with no way back
    //   through the interface.
    //
    // ResponseEntity<Void> plus noContent(): status 204 and an empty body.
    // WHY 204 and not 200 with a body: there is nothing left to describe. The
    // Angular side only needs to know it worked, and typing the method Void
    // makes it impossible to return a body by accident later.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        roleAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

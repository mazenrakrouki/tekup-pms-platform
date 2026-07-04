package com.pms.user.controller;

import com.pms.user.dto.UserContextResponse;
import com.pms.user.dto.UserRequest;
import com.pms.user.dto.UserResponse;
import com.pms.user.repository.RoleRepository;
import com.pms.user.service.UserCrudService;
import com.pms.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserCrudService userCrudService;
    private final RoleRepository roleRepository;

    // ── Profil de l'utilisateur connecté ─────────────────────────
    @GetMapping("/api/me/context")
    public ResponseEntity<UserContextResponse> getContext(@AuthenticationPrincipal String email) {
        return ResponseEntity.ok(userService.getContext(email));
    }

    // ── Rôles ─────────────────────────────────────────────────────
    @GetMapping("/api/roles")
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    public ResponseEntity<List<Map<String, Object>>> listRoles() {
        return ResponseEntity.ok(roleRepository.findAll().stream()
                .map(r -> Map.of("id", (Object) r.getId(), "name", r.getName()))
                .toList());
    }

    // ── CRUD Admin ────────────────────────────────────────────────
    @GetMapping("/api/users")
    public ResponseEntity<List<UserResponse>> list() {
        return ResponseEntity.ok(userCrudService.findAll());
    }

    @GetMapping("/api/users/assignable")
    public ResponseEntity<List<UserResponse>> assignable() {
        return ResponseEntity.ok(userCrudService.findAssignable());
    }

    @GetMapping("/api/users/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userCrudService.findById(id));
    }

    @PostMapping("/api/users")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        UserResponse created = userCrudService.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/api/users/{id}")
    public ResponseEntity<UserResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userCrudService.update(id, request));
    }

    @PatchMapping("/api/users/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        userCrudService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/users/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userCrudService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

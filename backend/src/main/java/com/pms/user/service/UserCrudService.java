package com.pms.user.service;

import com.pms.user.dto.UserCreateResult;
import com.pms.user.dto.UserRequest;
import com.pms.user.dto.UserResponse;
import com.pms.user.entity.User;
import com.pms.user.mapper.UserMapper;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import com.pms.shared.exception.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserCrudService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final CacheManager cacheManager;

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userMapper.toResponseList(userRepository.findAllActive());
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public Page<UserResponse> findAll(Pageable pageable) {
        return userRepository.findAllActivePaged(pageable).map(userMapper::toResponse);
    }

    /** Recherche paginée avec filtres optionnels (texte, rôle, statut actif). */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public Page<UserResponse> search(String search, Long roleId, Boolean active, Pageable pageable) {
        // Chaîne vide (jamais null) : évite l'inférence de type bytea de PostgreSQL sur un paramètre null dans LOWER().
        String normalized = (search == null) ? "" : search.trim();
        return userRepository.searchActive(normalized, roleId, active, pageable).map(userMapper::toResponse);
    }

    /**
     * Liste des utilisateurs actifs affectables (chef de projet / équipe).
     * Accessible à quiconque peut affecter — sans exiger MANAGE_USERS (réservé à l'admin),
     * pour que le Directeur/PM puisse peupler les sélecteurs d'affectation.
     */
    @PreAuthorize("hasAnyAuthority('ASSIGN_CHEF_PROJET','ASSIGN_DEVELOPER','MANAGE_USERS')")
    @Transactional(readOnly = true)
    public List<UserResponse> findAssignable() {
        return userMapper.toResponseList(userRepository.findAllActive());
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return userMapper.toResponse(loadUser(id));
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public UserCreateResult create(UserRequest request) {
        if (userRepository.existsByEmailAndDeletedFalse(request.email())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.email());
        }

        var role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new IllegalArgumentException("Rôle introuvable : " + request.roleId()));

        // H-2: generate a random initial password — never use a fixed default
        String initialPassword = generateSecurePassword();

        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(initialPassword))
                .active(true)
                .firstLogin(true)
                .role(role)
                .build();

        UserResponse created = userMapper.toResponse(userRepository.save(user));
        return new UserCreateResult(created, initialPassword);
    }

    /** Generates a 12-character random password containing upper, lower, digit, and special characters. */
    private static String generateSecurePassword() {
        SecureRandom rng = new SecureRandom();
        String upper   = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower   = "abcdefghijklmnopqrstuvwxyz";
        String digits  = "0123456789";
        String special = "@#$%!";
        String all     = upper + lower + digits + special;

        List<Character> chars = new ArrayList<>(12);
        // Guarantee complexity requirements
        chars.add(upper  .charAt(rng.nextInt(upper.length())));
        chars.add(lower  .charAt(rng.nextInt(lower.length())));
        chars.add(digits .charAt(rng.nextInt(digits.length())));
        chars.add(special.charAt(rng.nextInt(special.length())));
        for (int i = 4; i < 12; i++) {
            chars.add(all.charAt(rng.nextInt(all.length())));
        }
        Collections.shuffle(chars, rng);
        StringBuilder sb = new StringBuilder(12);
        chars.forEach(sb::append);
        return sb.toString();
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = loadUser(id);

        if (!user.getEmail().equals(request.email()) && userRepository.existsByEmailAndDeletedFalse(request.email())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.email());
        }

        var role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new IllegalArgumentException("Rôle introuvable : " + request.roleId()));

        // Mandate dynamique RBAC : changer de rôle invalide immédiatement la session existante
        boolean roleChanged = !user.getRole().getId().equals(request.roleId());
        String oldEmail = user.getEmail();   // capture before setEmail() — cache key uses old email
        int oldVersion = user.getTokenVersion();

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setRole(role);
        if (roleChanged) {
            user.revokeAllTokens();
        }

        User saved = userRepository.save(user);

        if (roleChanged) {
            evictFromCache(oldEmail, oldVersion);
        }

        return userMapper.toResponse(saved);
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public UserCreateResult resetAccount(Long id) {
        User user = loadUser(id);
        int oldVersion = user.getTokenVersion();
        String newPassword = generateSecurePassword();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFirstLogin(true);   // force password change on next login
        user.revokeAllTokens();     // invalidate all existing sessions immediately
        User saved = userRepository.save(user);
        evictFromCache(user.getEmail(), oldVersion);
        return new UserCreateResult(userMapper.toResponse(saved), newPassword);
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void deactivate(Long id) {
        User user = loadUser(id);
        int oldVersion = user.getTokenVersion();
        user.setActive(false);
        user.revokeAllTokens();
        userRepository.save(user);
        evictFromCache(user.getEmail(), oldVersion);
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void reactivate(Long id) {
        User user = loadUser(id);
        user.setActive(true);
        userRepository.save(user);
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void delete(Long id) {
        User user = loadUser(id);
        int oldVersion = user.getTokenVersion();
        user.setDeleted(true);
        user.revokeAllTokens();
        userRepository.save(user);
        evictFromCache(user.getEmail(), oldVersion);
    }

    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }

    /** Éviction du cache de contexte de sécurité pour l'entrée (email:version) révoquée (ADR-017). */
    private void evictFromCache(String email, int tokenVersion) {
        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + tokenVersion);
        }
    }
}

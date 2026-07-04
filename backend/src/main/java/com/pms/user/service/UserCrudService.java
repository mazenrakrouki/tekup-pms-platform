package com.pms.user.service;

import com.pms.user.dto.UserRequest;
import com.pms.user.dto.UserResponse;
import com.pms.user.entity.User;
import com.pms.user.mapper.UserMapper;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import com.pms.shared.exception.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserCrudService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userMapper.toResponseList(userRepository.findAllActive());
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
    public UserResponse create(UserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.email());
        }

        var role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new IllegalArgumentException("Rôle introuvable : " + request.roleId()));

        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .passwordHash(passwordEncoder.encode("Changeme1!"))
                .active(true)
                .firstLogin(true)
                .role(role)
                .build();

        return userMapper.toResponse(userRepository.save(user));
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = loadUser(id);

        if (!user.getEmail().equals(request.email()) && userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.email());
        }

        var role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new IllegalArgumentException("Rôle introuvable : " + request.roleId()));

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setRole(role);

        return userMapper.toResponse(userRepository.save(user));
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void deactivate(Long id) {
        User user = loadUser(id);
        user.setActive(false);
        userRepository.save(user);
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void delete(Long id) {
        User user = loadUser(id);
        user.setDeleted(true);
        userRepository.save(user);
    }

    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}

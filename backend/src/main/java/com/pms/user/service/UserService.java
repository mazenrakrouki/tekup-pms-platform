package com.pms.user.service;

import com.pms.shared.exception.NotFoundException;
import com.pms.user.dto.UserContextResponse;
import com.pms.user.entity.Permission;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserContextResponse getContext(String email) {
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + email));

        var permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());

        return new UserContextResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole().getName(),
                permissions,
                user.isFirstLogin()
        );
    }
}

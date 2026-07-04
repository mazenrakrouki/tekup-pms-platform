package com.pms.auth.dto;

import java.util.Set;

public record AuthResponse(
        Long userId,
        String accessToken,
        String refreshToken,
        boolean firstLogin,
        String email,
        String fullName,
        String role,
        Set<String> permissions
) {}

package com.pms.user.dto;

import java.util.Set;

public record UserContextResponse(
        Long id,
        String fullName,
        String email,
        String role,
        Set<String> permissions,
        boolean firstLogin
) {}

package com.pms.user.dto;

public record UserResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String roleName,
        boolean active,
        boolean firstLogin
) {}

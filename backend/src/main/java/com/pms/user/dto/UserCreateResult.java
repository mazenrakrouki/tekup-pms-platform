package com.pms.user.dto;

/** Returned only from POST /api/users — wraps the created user plus the one-time initial password. */
public record UserCreateResult(UserResponse user, String initialPassword) {}

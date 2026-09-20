package com.pms.user.dto;

// The only response in the application that carries a password in clear text: the created/reset
// account plus its one-time password. Returned by POST /api/users and PATCH
// /api/users/{id}/reset-account only — kept out of UserResponse so no other user-returning
// endpoint ever carries a password field. The password itself is never stored (only its BCrypt
// hash is), so this is the one moment the clear value exists outside the admin's screen; the
// server generates it (SecureRandom) rather than using a fixed default, per audit finding H-2.

/**
 * The created (or reset) account, plus its one-time password.
 *
 * <p>{@code initialPassword} is shown once and never retrievable again — re-reading the user later
 * gives only the hash-backed UserResponse. Because it's clear text, these two responses must never
 * be logged or cached.
 */
public record UserCreateResult(UserResponse user, String initialPassword) {}

package com.pms.auth.dto;

/** Internal carrier: HTTP body + the refresh token that goes into the HttpOnly cookie. */
public record TokenBundle(AuthResponse body, String refreshToken) {}

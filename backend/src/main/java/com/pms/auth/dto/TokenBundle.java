package com.pms.auth.dto;

/**
 * Porteur interne : corps HTTP + jeton de rafraichissement destine au cookie HttpOnly.
 *
 * <p>{@code refreshMaxAgeSeconds} accompagne le jeton pour que la duree du cookie et
 * celle du JWT soient decidees au meme endroit. Les separer laisserait un cookie
 * survivre a son contenu : l'utilisateur resterait "connecte" avec un jeton expire.
 */
public record TokenBundle(AuthResponse body, String refreshToken, int refreshMaxAgeSeconds) {}

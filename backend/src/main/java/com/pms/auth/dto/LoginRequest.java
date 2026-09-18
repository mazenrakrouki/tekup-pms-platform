package com.pms.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        /**
         * Session longue demandee par l'utilisateur ("se souvenir de moi").
         * Primitif : absent du JSON = false, donc les clients existants gardent
         * le comportement actuel sans modification.
         */
        boolean rememberMe
) {
    /**
     * Connexion sans session longue. Conserve la compatibilite des appelants
     * existants et rend explicite que l'absence du drapeau vaut {@code false}.
     */
    public LoginRequest(String email, String password) {
        this(email, password, false);
    }
}

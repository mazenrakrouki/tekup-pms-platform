package com.pms.shared.exception;

/**
 * Violation d'une règle métier (ex. "un projet terminé ne peut pas revenir à Actif",
 * "charge déjà validée"). Se mappe en HTTP 422 Unprocessable Entity.
 *
 * À distinguer de {@link IllegalArgumentException} qui reste réservé aux conflits
 * de données (doublon de code/email) et se mappe en 409 Conflict.
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}

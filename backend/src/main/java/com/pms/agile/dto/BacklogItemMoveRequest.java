package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Déplacement d'une carte sur le tableau : changement de colonne, et éventuellement
 * d'itération. Requête dédiée plutôt qu'un PUT complet, pour qu'un glisser-déposer
 * n'ait pas à renvoyer le titre, la description et l'estimation.
 */
public record BacklogItemMoveRequest(
        @NotNull BacklogItemStatus status,
        /** {@code null} renvoie l'élément au backlog produit. */
        Long sprintId
) {}

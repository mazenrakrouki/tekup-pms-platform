package com.pms.governance.dto;

// JSON the API sends back for one change request — the read-side mirror of
// DemandeChangementRequest. Flat on purpose: the entity carries lazy Project/User relations
// and returning it directly would either throw LazyInitializationException or leak the user's
// email/password hash/role. This record decides once what of each is exposed.

import com.pms.governance.entity.PrioriteChangement;
import com.pms.governance.entity.StatutChangement;

import java.time.LocalDate;

public record DemandeChangementResponse(
        Long id,
        Long projectId,
        String projectCode,
        Long demandeurId,
        // First+last name only, built by DemandeChangementMapper via a @Named helper — avoids
        // the frontend needing one extra /api/users/{id} call per row just to show a name.
        String demandeurFullName,
        String titre,
        String description,
        PrioriteChangement priorite,
        // EN_ATTENTE / APPROUVE / REJETE — also what drives whether the UI shows the
        // Approve/Reject buttons, mirroring the rule enforced server-side in the service.
        StatutChangement statut,
        LocalDate dateDemande,
        // Null while EN_ATTENTE; set by the service at the same moment as statut.
        LocalDate dateDecision
) {}

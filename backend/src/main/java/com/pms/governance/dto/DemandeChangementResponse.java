package com.pms.governance.dto;

import com.pms.governance.entity.PrioriteChangement;
import com.pms.governance.entity.StatutChangement;

import java.time.LocalDate;

public record DemandeChangementResponse(
        Long id,
        Long projectId,
        String projectCode,
        Long demandeurId,
        String demandeurFullName,
        String titre,
        String description,
        PrioriteChangement priorite,
        StatutChangement statut,
        LocalDate dateDemande,
        LocalDate dateDecision
) {}

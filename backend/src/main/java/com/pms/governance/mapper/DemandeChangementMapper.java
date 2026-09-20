package com.pms.governance.mapper;

import com.pms.governance.dto.DemandeChangementResponse;
import com.pms.governance.entity.DemandeChangement;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

// Maps DemandeChangement (table "demandes_changement", V11) to the flat DemandeChangementResponse
// DTO. Returning the entity directly would publish the whole User (demandeur) object - email,
// password hash, role - so this mapper sends only demandeurId and demandeurFullName. MapStruct
// (ADR-018) generates DemandeChangementMapperImpl from these rules at compile time.
@Mapper(componentModel = "spring")
public interface DemandeChangementMapper {

    /**
     * id/titre/description/priorite/statut/dateDemande/dateDecision copy automatically (same
     * names); the four @Mapping rules flatten project and demandeur. JOIN FETCH in the repository
     * keeps this from firing extra SELECTs on the two LAZY relations (N+1 queries).
     */
    @Mapping(target = "projectId",         source = "project.id")
    @Mapping(target = "projectCode",       source = "project.code")
    @Mapping(target = "demandeurId",       source = "demandeur.id")
    @Mapping(target = "demandeurFullName", source = "demandeur", qualifiedByName = "fullName")
    DemandeChangementResponse toResponse(DemandeChangement dc);

    // Reuses the same @Mapping rules as toResponse; called by findByProject() to keep the
    // repository's "ORDER BY dateDemande DESC" order, newest first.
    List<DemandeChangementResponse> toResponseList(List<DemandeChangement> list);

    // Named so @Mapping can reference it unambiguously (qualifiedByName); guards null since
    // MapStruct calls it without checking demandeur first.
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

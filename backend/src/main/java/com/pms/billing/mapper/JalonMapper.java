package com.pms.billing.mapper;

import com.pms.billing.dto.JalonResponse;
import com.pms.billing.entity.JalonFacturation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

// Translates the JalonFacturation entity into the flat JalonResponse DTO. Interface only:
// MapStruct generates JalonMapperImpl at compile time, checked against both types so an
// unmapped field warns at build instead of silently sending null. Returning the entity directly
// would risk a LazyInitializationException on the LAZY project link and leak the whole Project
// (budget, client, team...) into a single-milestone response.
//
// Does not compute the amount (that's JalonService.computeMontant/recomputePrevuMontants,
// marker H-4 — the mapper only copies whatever value it's given) and does not check permissions
// (hasAuthority('VIEW_BILLING')/'MANAGE_BILLING') sits on the service, plus ProjectScopeInterceptor
// per project, ADR-021). AvenantMapper and PaiementMapper follow the same pattern.
@Mapper(componentModel = "spring")
public interface JalonMapper {

    /*
     * id, label, pourcentage, montant, datePrevue, dateFacture and statut copy automatically
     * (same name and, for statut, same enum on both sides — @Enumerated(EnumType.STRING) on the
     * entity keeps the stored word and the JSON value in sync). projectId/projectCode are
     * flattened from the LAZY project link below; JalonFacturationRepository's "JOIN FETCH
     * j.project" makes this free instead of firing an extra SELECT per row.
     * @Mapper(componentModel = "spring") lets Spring inject this into JalonService as a bean.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    JalonResponse toResponse(JalonFacturation jalon);

    // Maps a whole list with the same @Mapping rules as toResponse. Called by
    // JalonService.findByProject() to fill the billing schedule table, preserving the
    // repository's datePrevue ordering (NULLS LAST, then id).
    List<JalonResponse> toResponseList(List<JalonFacturation> jalons);
}

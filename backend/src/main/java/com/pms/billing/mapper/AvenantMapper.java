package com.pms.billing.mapper;

import com.pms.billing.dto.AvenantResponse;
import com.pms.billing.entity.Avenant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

// Translates the Avenant entity into the flat AvenantResponse DTO sent to the browser. This is
// an interface only: MapStruct generates AvenantMapperImpl at compile time (checked against
// both types, so an unmapped field warns at build time instead of silently sending null).
// Returning the entity directly would risk a LazyInitializationException on Avenant.project
// (LAZY) and would leak the whole Project object into a response meant to show one amendment.
//
// No permission check here, by design: authorization is dynamic and permission-based
// (hasAuthority('VIEW_BILLING')/'MANAGE_BILLING') on the service method, plus
// ProjectScopeInterceptor per project (ADR-021) — this mapper runs only after both gates.
// JalonMapper and PaiementMapper follow the same pattern for milestones and payments.
@Mapper(componentModel = "spring")
public interface AvenantMapper {

    /*
     * id, numero, objet, montant, workloadDays and dateAvenant copy automatically (same name on
     * both sides). projectId/projectCode are flattened from the LAZY Avenant.project below —
     * AvenantRepository's "JOIN FETCH a.project" makes this free instead of an extra SELECT.
     * @Mapper(componentModel = "spring") is required so Spring can find a bean to inject into
     * AvenantService; without it the app fails to start with NoSuchBeanDefinitionException.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    AvenantResponse toResponse(Avenant avenant);

    // Maps a whole list with the same @Mapping rules as toResponse, so the two can't drift apart.
    // Called by AvenantService.findByProject() to fill a project's "Avenants" table.
    List<AvenantResponse> toResponseList(List<Avenant> avenants);
}

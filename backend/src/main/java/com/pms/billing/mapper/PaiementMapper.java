package com.pms.billing.mapper;

import com.pms.billing.dto.PaiementResponse;
import com.pms.billing.entity.Paiement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

// Translates the Paiement entity into the flat PaiementResponse DTO. Interface only: MapStruct
// generates PaiementMapperImpl at compile time, checked against both types. Returning the
// entity directly would risk a LazyInitializationException on the LAZY jalon link and would
// drag the whole milestone (and, behind it, the project) into one payment line.
//
// Does not decide the milestone status (JalonService.recalculerStatut does, on write, after
// each payment save/delete) and does not check permissions (hasAuthority('VIEW_BILLING')/
// 'MANAGE_BILLING') on the service, plus ProjectScopeInterceptor, ADR-021). The simplest of the
// three billing mappers: only jalonId is exposed, no project code, since payments are always
// shown inside a milestone the user already has open.
@Mapper(componentModel = "spring")
public interface PaiementMapper {

    /*
     * id, montantRecu, datePaiement and reference copy automatically (same name, same
     * BigDecimal type — no double conversion that could shift cents). jalonId is flattened from
     * the LAZY jalon link below; since only getId() is read, Hibernate answers from the proxy
     * itself with no extra SELECT, so no JOIN FETCH is needed here (unlike AvenantRepository/
     * JalonFacturationRepository, which do read a real column). Adding a mapping that reads a
     * real jalon field later would end that free ride.
     */
    @Mapping(target = "jalonId", source = "jalon.id")
    PaiementResponse toResponse(Paiement paiement);

    // Maps a whole list with the same @Mapping rule as toResponse. Called by
    // PaiementService.findByJalon(), preserving the repository's payment-date ordering.
    List<PaiementResponse> toResponseList(List<Paiement> paiements);
}

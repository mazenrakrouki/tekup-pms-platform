package com.pms.governance.mapper;

import com.pms.governance.dto.PartiePrenanteResponse;
import com.pms.governance.entity.PartiePrenante;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

// Maps PartiePrenante (table "parties_prenantes", V11) to the flat PartiePrenanteResponse DTO.
// Returning the entity directly would risk a LazyInitializationException on its LAZY Project link
// and leak BaseEntity bookkeeping fields. MapStruct (ADR-018) generates PartiePrenanteMapperImpl
// from these rules.
@Mapper(componentModel = "spring")
public interface PartiePrenanteMapper {

    /**
     * id/nom/fonction/email/telephone/influence/interet copy automatically (same names); the two
     * @Mapping rules flatten project.id/project.code. The repository's JOIN FETCH keeps this from
     * firing an extra SELECT on the LAZY project relation.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    PartiePrenanteResponse toResponse(PartiePrenante pp);

    // Reuses the same @Mapping rules as toResponse; called by findByProject() to keep the
    // repository's "ORDER BY nom" alphabetical order.
    List<PartiePrenanteResponse> toResponseList(List<PartiePrenante> list);
}

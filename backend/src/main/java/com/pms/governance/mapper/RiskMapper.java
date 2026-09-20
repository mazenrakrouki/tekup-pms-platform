package com.pms.governance.mapper;

import com.pms.governance.dto.RiskResponse;
import com.pms.governance.entity.Risk;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

// Maps Risk (table "risks", V11) to the flat RiskResponse DTO. Returning the entity directly
// would risk a LazyInitializationException on its LAZY Project link and leak BaseEntity
// bookkeeping fields. MapStruct (ADR-018) generates RiskMapperImpl from these rules.
@Mapper(componentModel = "spring")
public interface RiskMapper {

    /**
     * id/description/probabilite/impact/planMitigation/statut copy automatically (same names);
     * the two @Mapping rules flatten project.id/project.code. The repository's JOIN FETCH keeps
     * this from firing an extra SELECT on the LAZY project relation.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    RiskResponse toResponse(Risk risk);

    // Reuses the same @Mapping rules as toResponse; called by findByProject() to keep the
    // repository's creation-date-descending order.
    List<RiskResponse> toResponseList(List<Risk> risks);
}

package com.pms.governance.mapper;

import com.pms.governance.dto.LivrableResponse;
import com.pms.governance.entity.Livrable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

// Maps Livrable (table "livrables", V11) to the flat LivrableResponse DTO. Returning the entity
// directly would risk a LazyInitializationException on its LAZY Project link and leak BaseEntity
// bookkeeping fields. MapStruct (ADR-018) generates LivrableMapperImpl from these rules.
@Mapper(componentModel = "spring")
public interface LivrableMapper {

    /**
     * id/titre/description/dateEcheance/statut copy automatically (same names); the two @Mapping
     * rules flatten project.id/project.code. LivrableRepository's JOIN FETCH keeps this from
     * firing an extra SELECT on the LAZY project relation.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    LivrableResponse toResponse(Livrable livrable);

    // Reuses the same @Mapping rules as toResponse; called by findByProject() to keep the
    // repository's "ORDER BY dateEcheance NULLS LAST, titre" order.
    List<LivrableResponse> toResponseList(List<Livrable> livrables);
}

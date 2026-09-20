package com.pms.mission.mapper;

import com.pms.mission.dto.ComposanteResponse;
import com.pms.mission.entity.ComposanteMission;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Translates ComposanteMission entities into flat ComposanteResponse records for JSON. Without
 * it, the controller would have to return the entity itself (leaking the parent Mission's
 * Project and User, and risking LazyInitializationException on lazy fields) or hand-roll a
 * mapping that could drift from the DTO whenever a field is added. Generated at compile time by
 * MapStruct (ADR-018), so a mismatched field fails the build instead of silently sending null.
 * Sister of MissionMapper, which maps the parent trip one level up.
 */
@Mapper(componentModel = "spring")
public interface ComposanteMapper {

    // @Mapper(componentModel = "spring") registers the generated impl as a Spring bean;
    // without it ComposanteService can't get one injected and startup fails. Most fields
    // (typeComposante, montant, devise, description, id) copy automatically by matching names;
    // missionId is the one exception, mapped below from mission.id so only the number — not the
    // lazy Mission object — crosses into the response (MapStruct null-guards the nested read).
    @Mapping(target = "missionId", source = "mission.id")
    ComposanteResponse toResponse(ComposanteMission composante);

    // Maps a whole list, reusing the same @Mapping rule as toResponse() so the two can never
    // drift apart. Order is preserved from the repository query (ORDER BY c.typeComposante).
    List<ComposanteResponse> toResponseList(List<ComposanteMission> composantes);
}

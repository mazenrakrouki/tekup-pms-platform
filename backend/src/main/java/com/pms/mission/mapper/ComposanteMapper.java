package com.pms.mission.mapper;

import com.pms.mission.dto.ComposanteResponse;
import com.pms.mission.entity.ComposanteMission;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ComposanteMapper {

    @Mapping(target = "missionId", source = "mission.id")
    ComposanteResponse toResponse(ComposanteMission composante);

    List<ComposanteResponse> toResponseList(List<ComposanteMission> composantes);
}

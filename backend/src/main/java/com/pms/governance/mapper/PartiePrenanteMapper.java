package com.pms.governance.mapper;

import com.pms.governance.dto.PartiePrenanteResponse;
import com.pms.governance.entity.PartiePrenante;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface PartiePrenanteMapper {

    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    PartiePrenanteResponse toResponse(PartiePrenante pp);

    List<PartiePrenanteResponse> toResponseList(List<PartiePrenante> list);
}

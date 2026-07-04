package com.pms.billing.mapper;

import com.pms.billing.dto.AvenantResponse;
import com.pms.billing.entity.Avenant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface AvenantMapper {

    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    AvenantResponse toResponse(Avenant avenant);

    List<AvenantResponse> toResponseList(List<Avenant> avenants);
}

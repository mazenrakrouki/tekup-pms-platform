package com.pms.governance.mapper;

import com.pms.governance.dto.LivrableResponse;
import com.pms.governance.entity.Livrable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface LivrableMapper {

    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    LivrableResponse toResponse(Livrable livrable);

    List<LivrableResponse> toResponseList(List<Livrable> livrables);
}

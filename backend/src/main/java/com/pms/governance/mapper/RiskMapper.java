package com.pms.governance.mapper;

import com.pms.governance.dto.RiskResponse;
import com.pms.governance.entity.Risk;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface RiskMapper {

    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    RiskResponse toResponse(Risk risk);

    List<RiskResponse> toResponseList(List<Risk> risks);
}

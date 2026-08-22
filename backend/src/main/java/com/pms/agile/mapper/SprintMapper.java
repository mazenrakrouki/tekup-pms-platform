package com.pms.agile.mapper;

import com.pms.agile.dto.SprintResponse;
import com.pms.agile.entity.Sprint;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SprintMapper {

    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    SprintResponse toResponse(Sprint sprint);

    List<SprintResponse> toResponseList(List<Sprint> sprints);
}

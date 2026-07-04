package com.pms.billing.mapper;

import com.pms.billing.dto.JalonResponse;
import com.pms.billing.entity.JalonFacturation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface JalonMapper {

    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    JalonResponse toResponse(JalonFacturation jalon);

    List<JalonResponse> toResponseList(List<JalonFacturation> jalons);
}

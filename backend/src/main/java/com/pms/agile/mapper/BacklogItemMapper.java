package com.pms.agile.mapper;

import com.pms.agile.dto.BacklogItemResponse;
import com.pms.agile.entity.BacklogItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BacklogItemMapper {

    // sprint is nullable; MapStruct emits a null-safe navigation for the two
    // sprint fields, so an item in the product backlog maps to nulls rather
    // than throwing.
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    @Mapping(target = "sprintId",    source = "sprint.id")
    @Mapping(target = "sprintName",  source = "sprint.name")
    BacklogItemResponse toResponse(BacklogItem item);

    List<BacklogItemResponse> toResponseList(List<BacklogItem> items);
}

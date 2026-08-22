package com.pms.agile.mapper;

import com.pms.agile.dto.BacklogItemResponse;
import com.pms.agile.entity.BacklogItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BacklogItemMapper {

    // sprintId / sprintName restent null lorsque l'élément n'est engagé dans aucun sprint :
    // MapStruct insère les vérifications de nullité sur les chemins imbriqués.
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    @Mapping(target = "sprintId",    source = "sprint.id")
    @Mapping(target = "sprintName",  source = "sprint.name")
    BacklogItemResponse toResponse(BacklogItem item);

    List<BacklogItemResponse> toResponseList(List<BacklogItem> items);
}

package com.pms.agile.mapper;

import com.pms.agile.dto.BacklogItemResponse;
import com.pms.agile.entity.BacklogItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * MapStruct translation from the BacklogItem entity to the flat BacklogItemResponse sent to
 * the browser. Avoids returning the entity directly, which would leak the linked Project
 * (budget, client) and User (email, password hash) and would throw LazyInitializationException
 * once Jackson touches a lazy link after the transaction closes.
 * No toEntity(BacklogItemRequest): that direction is written by hand in BacklogItemService,
 * because resolveSprint/resolveAssignee must first verify the ids in the body belong to this
 * project — a generated mapper would just trust them.
 */
// componentModel = "spring" so the generated BacklogItemMapperImpl is a Spring bean that
// BacklogItemService can receive through constructor injection.
@Mapper(componentModel = "spring")
public interface BacklogItemMapper {

    /**
     * Flattens one item. id/title/description/priority/estimateDays/status copy by name;
     * the @Mapping lines below pull values out of the linked project/sprint/assignee objects
     * (sprint and assignee may be null — MapStruct null-checks each path step automatically).
     */
    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "sprintId",     source = "sprint.id")
    @Mapping(target = "sprintName",   source = "sprint.name")
    @Mapping(target = "assigneeId",   source = "assignee.id")
    @Mapping(target = "assigneeName", source = "assignee.fullName")
    BacklogItemResponse toResponse(BacklogItem item);

    /** Maps a whole board's worth of items, preserving the repository's id order. */
    List<BacklogItemResponse> toResponseList(List<BacklogItem> items);
}

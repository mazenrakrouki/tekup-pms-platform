package com.pms.team.mapper;

import com.pms.team.dto.TeamAssignmentResponse;
import com.pms.team.entity.TeamAssignment;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TeamAssignmentMapper {

    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "projectName",  source = "project.name")
    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user", qualifiedByName = "fullName")
    TeamAssignmentResponse toResponse(TeamAssignment ta);

    List<TeamAssignmentResponse> toResponseList(List<TeamAssignment> list);

    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

package com.pms.team.mapper;

import com.pms.team.dto.TeamAssignmentResponse;
import com.pms.team.entity.TeamAssignment;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

// Translates TeamAssignment entities into flat TeamAssignmentResponse DTOs. MapStruct generates
// the implementation at compile time (ADR-018), so a field added to the response and forgotten
// here fails the build instead of silently sending null to the browser.
@Mapper(componentModel = "spring")
public interface TeamAssignmentMapper {

    /**
     * Flattens one row, pulling project/user labels out of the nested (LAZY) entities. Returning
     * the entity instead would leak User.passwordHash/Role and risk LazyInitializationException
     * once the transaction closes — this is why callers rely on JOIN FETCH upstream.
     */
    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "projectName",  source = "project.name")
    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user", qualifiedByName = "fullName")
    TeamAssignmentResponse toResponse(TeamAssignment ta);

    List<TeamAssignmentResponse> toResponseList(List<TeamAssignment> list);

    // Named explicitly so MapStruct doesn't have to guess which User->String method to use if a
    // second one is ever added. Null-safe because MapStruct calls it without checking ta.getUser() first.
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

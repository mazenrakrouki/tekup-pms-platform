package com.pms.mission.mapper;

import com.pms.mission.dto.MissionResponse;
import com.pms.mission.entity.Mission;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MissionMapper {

    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user", qualifiedByName = "fullName")
    MissionResponse toResponse(Mission mission);

    List<MissionResponse> toResponseList(List<Mission> missions);

    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

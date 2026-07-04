package com.pms.user.mapper;

import com.pms.user.dto.ResourceResponse;
import com.pms.user.entity.Resource;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ResourceMapper {

    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user",     qualifiedByName = "fullName")
    @Mapping(target = "annualCost",   expression = "java(resource.getAnnualCost())")
    ResourceResponse toResponse(Resource resource);

    List<ResourceResponse> toResponseList(List<Resource> resources);

    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

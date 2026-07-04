package com.pms.user.mapper;

import com.pms.user.dto.UserResponse;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "roleName", source = "role.name")
    UserResponse toResponse(User user);

    List<UserResponse> toResponseList(List<User> users);
}

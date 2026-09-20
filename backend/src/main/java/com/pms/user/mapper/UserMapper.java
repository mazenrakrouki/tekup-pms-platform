package com.pms.user.mapper;

import com.pms.user.dto.UserResponse;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

// Maps User entities to the UserResponse DTO sent to the Angular front end.
// UserResponse declares neither passwordHash nor tokenVersion, so MapStruct never copies them
// into a JSON answer (ADR-018 also makes MapStruct the only allowed entity<->DTO mapper).
// Sister mapper: ResourceMapper, same package, same job for Resource/ResourceResponse.
// componentModel = "spring" makes the generated UserMapperImpl a Spring bean, which is how
// UserCrudService receives it via constructor injection.
@Mapper(componentModel = "spring")
public interface UserMapper {

    /**
     * Copies one User into a UserResponse (id, firstName, lastName, email, roleName, active,
     * firstLogin) — no password hash, no token version. Only roleName needs an explicit rule.
     */
    // roleName has no matching getter on User; read via role.name. Costs nothing extra since
    // User.role is EAGER and already loaded.
    // This string is a display label only — authorization always checks permissions
    // (@PreAuthorize("hasAuthority(...)")), never role names.
    @Mapping(target = "roleName", source = "role.name")
    UserResponse toResponse(User user);

    /**
     * Maps a whole list in one call; keeps UserCrudService.findAll()/findAssignable() to a
     * single line each.
     */
    List<UserResponse> toResponseList(List<User> users);
}

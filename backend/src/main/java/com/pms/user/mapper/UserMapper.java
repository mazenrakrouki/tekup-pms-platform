package com.pms.user.mapper;

import com.pms.user.dto.UserResponse;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/*
 * UserMapper -- turns a User database entity into the UserResponse DTO that the
 * REST API sends back to the Angular front end.
 * (DTO = Data Transfer Object: a small read-only object built only to travel over
 * HTTP. It holds plain values, no database behaviour.)
 *
 * Where it sits in the flow:
 *     UserController -> UserCrudService -> UserMapper -> UserResponse -> JSON
 * UserCrudService is the only class that uses it (findAll, search, findById,
 * create, update, resetAccount). It is also handed to Page.map(...), so Spring
 * Data turns a page of User rows into a page of UserResponse objects.
 * This mapper calls nothing else: no repository, no database access of its own.
 *
 * Why it exists. Two reasons.
 * 1) Safety. The User entity carries passwordHash and tokenVersion. UserResponse
 *    does not declare those fields, so MapStruct never copies them and they can
 *    never end up in a JSON answer.
 * 2) ADR-018 makes MapStruct the only allowed way to map entity <-> DTO in this
 *    project. The generated code is checked by the compiler, so a field renamed
 *    on one side breaks the build instead of quietly sending null.
 * If this file were deleted, UserCrudService would not compile and every screen
 * of the user administration area would stop working.
 *
 * Sister mapper: ResourceMapper, in this same package, does the same job for the
 * Resource entity.
 */
// WHAT: @Mapper asks the MapStruct annotation processor to generate the real
// implementation class (UserMapperImpl) while the project is compiled, so there
// is no reflection and no mapping library running at request time.
// WHY componentModel = "spring": it makes that generated class a Spring
// @Component, which is what allows UserCrudService to receive it through its
// constructor (Lombok @RequiredArgsConstructor).
// WITHOUT IT: the generated class would not be a Spring bean and the application
// would refuse to start with "no qualifying bean of type UserMapper".
@Mapper(componentModel = "spring")
public interface UserMapper {

    /**
     * Copies one User entity into one UserResponse record.
     * Gives back a UserResponse that holds id, firstName, lastName, email,
     * roleName, active and firstLogin -- and no password hash, no token version.
     *
     * Fields whose names match on both sides are copied automatically. Only
     * roleName needs the rule written below, because User has no getRoleName().
     *
     * Why the method has no body: MapStruct writes it for us at build time. The
     * obvious alternative, typing "new UserResponse(user.getId(), ...)" by hand,
     * is forbidden by ADR-018: if someone later adds a field to the record, the
     * hand-written call would still compile and silently send null.
     */
    // WHAT: reads user.getRole().getName() and puts that text into
    // UserResponse.roleName ("ADMIN", "DIRECTEUR", "CHEF_PROJET"...).
    // WHY: the admin table needs a readable role label, but the whole Role object
    // must not travel, because it carries the complete permission set. Reading it
    // here costs nothing: User.role is mapped with FetchType.EAGER, so it is
    // already loaded and no extra SELECT is fired.
    // WITHOUT IT: MapStruct would find no source for "roleName", leave it null,
    // and the user list would show an empty role column for everybody.
    //
    // Important for a jury question: this string is a LABEL for display only.
    // Authorization never compares role names. It always checks permissions, with
    // @PreAuthorize("hasAuthority('MANAGE_USERS')") placed on the service methods
    // of UserCrudService.
    @Mapping(target = "roleName", source = "role.name")
    UserResponse toResponse(User user);

    /**
     * Maps a whole list in one call. MapStruct generates the loop that applies
     * toResponse() to each element and collects the results.
     *
     * Why declare it instead of writing
     * users.stream().map(mapper::toResponse).toList() in the service: the
     * generated loop is the mapping code ADR-018 asks for, and it keeps
     * UserCrudService.findAll() and findAssignable() down to a single line.
     */
    List<UserResponse> toResponseList(List<User> users);
}

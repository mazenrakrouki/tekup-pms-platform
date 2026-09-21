package com.pms.user.mapper;

import com.pms.user.dto.ResourceResponse;
import com.pms.user.entity.Resource;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

// Maps Resource entities to the ResourceResponse DTO sent to the Angular front end.
// ADR-018 makes MapStruct the only allowed entity<->DTO mapper; here it also matters for
// safety, since Resource points at a whole User row carrying passwordHash and tokenVersion,
// which ResourceResponse never declares and therefore can never leak into JSON.
// Sister mapper: UserMapper, same package, same job for User/UserResponse.
// componentModel = "spring" makes the generated ResourceMapperImpl a Spring bean, which is
// how ResourceService receives it via constructor injection.
@Mapper(componentModel = "spring")
public interface ResourceMapper {

    /**
     * Copies one Resource into a ResourceResponse (id, userId, userFullName, dailyRate,
     * tccRate, dailyLoadedCost, staffingStart/End) without exposing the linked User itself.
     *
     * <p>Resource.user is lazy and open-in-view is false, so callers must invoke this mapper
     * while still inside the loading @Transactional method (or use a JOIN FETCH query first) —
     * reading user fields afterward throws LazyInitializationException.
     */
    // Front end needs the linked user's id to pre-select them in the edit form, without the
    // User object itself traveling (it carries the password hash).
    @Mapping(target = "userId",       source = "user.id")
    // qualifiedByName picks the fullName() helper below since MapStruct can't turn a User into
    // a String on its own.
    @Mapping(target = "userFullName", source = "user",     qualifiedByName = "fullName")
    // dailyLoadedCost is derived, never stored (dailyRate x (1+tccRate)); calling the getter here
    // pins the rule at the point the value leaves the backend. This starts from the resource's
    // BASE rate — ResourceService.withCurrentYearRates then overrides dailyRate/tccRate/
    // dailyLoadedCost together with this year's tcc_annuels row when one exists, so what the
    // caller actually receives is the effective rate, not necessarily this base figure.
    @Mapping(target = "dailyLoadedCost", expression = "java(resource.getDailyLoadedCost())")
    ResourceResponse toResponse(Resource resource);

    /**
     * Maps a whole list in one call; keeps both branches of ResourceService.findAll() (the
     * full referential vs. the ADR-021 project-manager scope) to one line each.
     */
    List<ResourceResponse> toResponseList(List<Resource> resources);

    /**
     * Display name (first + last) of a user, or null when there is no user.
     * Written as a default method rather than {@code source = "user.fullName"} so the null
     * case is explicit and testable.
     */
    // @Named must match the qualifiedByName label on the @Mapping above.
    @Named("fullName")
    default String fullName(User user) {
        // Guards a Resource built without a user (unit test, partial query); user_id is NOT
        // NULL in production so this never fires there.
        return user != null ? user.getFullName() : null;
    }
}

package com.pms.user.mapper;

import com.pms.user.dto.ResourceResponse;
import com.pms.user.entity.Resource;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/*
 * ResourceMapper -- turns a Resource database entity into the ResourceResponse
 * DTO that the REST API sends back to the Angular front end.
 * (DTO = Data Transfer Object: a small flat read-only object built only to
 * travel over HTTP as JSON. It holds plain values, no database behaviour.)
 *
 * Where it sits in the flow:
 *     ResourceController -> ResourceService -> ResourceMapper -> ResourceResponse -> JSON
 * ResourceService is the only class that uses it (findAll, findById, create,
 * update). The mapper itself calls nothing: no repository, no query of its own.
 * It only reads the Resource object the service has already loaded.
 *
 * What a Resource is (ADR-022): the User entity is the ACCOUNT (who can sign
 * in); the Resource is what that person COSTS -- daily rate, TCC rate (the
 * overhead coefficient, "taux de cout charge") and staffing dates. The two are
 * deliberately kept as separate entities, which is why this mapper has to glue
 * a little of the user back in (id and display name) for the resources screen.
 *
 * Why it exists. Two reasons.
 * 1) ADR-018 makes MapStruct the only allowed way to map entity <-> DTO in this
 *    project. MapStruct writes the copy code at compile time, so a field
 *    renamed on one side breaks the build instead of quietly sending null.
 * 2) Safety. A Resource points at a whole User row, and that row carries
 *    passwordHash and tokenVersion. ResourceResponse declares neither, so those
 *    two values can never reach a JSON answer.
 * If this file were deleted, ResourceService would not compile and the whole
 * resources / TCC screen would stop working.
 *
 * Sister mapper: UserMapper, in this same package, does the same job for the
 * User entity and produces UserResponse.
 */
// WHAT: @Mapper asks the MapStruct annotation processor to generate the real
// implementation class (ResourceMapperImpl) while the project is compiled, so
// there is no reflection and no mapping library running at request time.
// WHY componentModel = "spring": it makes that generated class a Spring
// @Component, which is what allows ResourceService to receive it through its
// constructor (Lombok @RequiredArgsConstructor).
// WITHOUT IT: the generated class would not be a Spring bean and the
// application would refuse to start with "no qualifying bean of type
// ResourceMapper".
@Mapper(componentModel = "spring")
public interface ResourceMapper {

    /**
     * Copies one Resource entity into one ResourceResponse record.
     * Gives back id, userId, userFullName, dailyRate, tccRate, annualCost,
     * staffingStart and staffingEnd -- and nothing else of the linked User.
     *
     * Fields whose names match on both sides are copied with no rule to write:
     * dailyRate, tccRate, staffingStart, staffingEnd, and id which Resource
     * inherits from BaseEntity. Only the three values below need help.
     *
     * Why the method has no body: MapStruct writes it for us at build time. The
     * obvious alternative, typing "new ResourceResponse(...)" by hand, is
     * forbidden by ADR-018: the record takes eight values in a fixed order, and
     * swapping dailyRate and tccRate would compile without a single warning.
     *
     * Careful about lazy loading: Resource.user is @OneToOne(fetch = LAZY) and
     * application.yml sets open-in-view: false, so the database session is
     * already closed once a service method has returned. This is why the list
     * queries (findAllActive, findVisibleToProjectManager) use JOIN FETCH
     * r.user, and why create() and update() call this mapper while they are
     * still inside their @Transactional method. Reading user.getId() or
     * user.getFullName() after the transaction has closed would throw
     * LazyInitializationException, and the resources list would answer 500
     * instead of the table.
     */
    // WHAT: copies the primary key of the linked user into userId.
    // WHY: the front end needs that identifier to pre-select the right person
    // in the edit form and to link a resource back to its account, but the User
    // object itself must not travel, because it carries the password hash.
    // WITHOUT IT: MapStruct would find no source for "userId", leave it null,
    // and the "modify this resource" form would open with an empty person.
    @Mapping(target = "userId",       source = "user.id")
    // WHAT: takes the whole User object as the source and hands it to the
    // fullName() method declared at the bottom of this file; the result fills
    // userFullName ("Jean Dupont").
    // WHY qualifiedByName: it names WHICH method must be used. It is needed
    // because the source is a User and the target is a String, and MapStruct
    // has no built-in way to turn one into the other.
    // WITHOUT IT: the build fails with a "cannot map property User user to
    // String userFullName" error -- MapStruct refuses rather than guessing --
    // and the resources table would have nothing to show in its name column.
    @Mapping(target = "userFullName", source = "user",     qualifiedByName = "fullName")
    // WHAT: expression = "java(...)" copies that piece of Java as-is into the
    // generated mapper, so annualCost is filled by calling the entity method
    // Resource.getAnnualCost().
    // WHY: the annual charged cost is DERIVED when read, never stored. The
    // resources table created by V3__schema_user_resource.sql has daily_rate
    // and tcc_rate but no annual_cost column; getAnnualCost() computes
    // dailyRate x (1 + tccRate) x 218 working days each time it is asked for.
    // Writing the call here makes that rule visible at the exact point where
    // the number leaves the backend, and pins it: if getAnnualCost() is ever
    // renamed, this generated line stops compiling.
    // WITHOUT IT: the field would depend on MapStruct finding that getter by
    // itself, so the day the getter is renamed or moved the amount would
    // silently become null and the screen would show a blank annual cost with
    // a green build.
    // Note for a jury question: this amount is INDICATIVE, and it is shown as
    // one column of the resources table. It multiplies the single dailyRate and
    // tccRate stored on the resource by 218 days. Cost and margin KPIs never
    // use it: KpiService prices each charge line with the rate of the year that
    // line belongs to (TccAnnuel, F-AFF-13 section 6.3 rule 4), and falls back
    // to these base rates only when that year has no rate of its own.
    @Mapping(target = "annualCost",   expression = "java(resource.getAnnualCost())")
    ResourceResponse toResponse(Resource resource);

    /**
     * Maps a whole list in one call. MapStruct generates the loop that applies
     * toResponse() to each element and collects the results.
     *
     * Why declare it instead of writing
     * resources.stream().map(mapper::toResponse).toList() in the service: the
     * generated loop is the mapping code ADR-018 asks for, and it keeps both
     * branches of ResourceService.findAll() down to one line each -- the full
     * referential for holders of MANAGE_RESOURCES, and the narrower list a
     * project manager may see under the data scope of ADR-021.
     */
    List<ResourceResponse> toResponseList(List<Resource> resources);

    /**
     * Gives back the display name of a user (first name, one space, last name),
     * or null when there is no user.
     *
     * Why a "default" method inside the interface: MapStruct copies it into the
     * generated implementation and calls it from toResponse(). That is how a
     * small piece of hand-written logic -- here the null check -- can live
     * inside a mapper that is otherwise fully generated.
     *
     * The obvious alternative, source = "user.fullName" on the @Mapping above,
     * would read the very same getter but would leave the null case to the
     * generator. Writing the method keeps that decision visible and testable.
     */
    // WHAT: @Named gives this method the label "fullName", which is exactly the
    // label the @Mapping above points at with qualifiedByName.
    // WHY: it makes the choice of helper explicit instead of letting MapStruct
    // pick any method of this interface that happens to take a User and return
    // a String.
    // WITHOUT IT: the build fails on toResponse(), which asks by name for a
    // qualifier called "fullName" that no method would carry; and the day a
    // second User -> String helper is added here, the generator would have two
    // candidates and no way to choose between them.
    @Named("fullName")
    default String fullName(User user) {
        // Null check. user_id is NOT NULL in the database, so a saved resource
        // always has one; the guard covers the other cases -- a Resource built
        // in a unit test without a user, or a future query that maps a
        // partially filled object. It gives back null instead of throwing a
        // NullPointerException in the middle of building the HTTP answer, which
        // would turn one missing name into a 500 for the whole resources list.
        return user != null ? user.getFullName() : null;
    }
}

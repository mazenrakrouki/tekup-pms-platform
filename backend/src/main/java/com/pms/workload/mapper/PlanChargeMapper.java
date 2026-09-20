package com.pms.workload.mapper;

import com.pms.user.entity.User;
import com.pms.workload.dto.PlanChargeResponse;
import com.pms.workload.entity.PlanCharge;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/*
 * MapStruct rules translating PlanCharge entities to flat PlanChargeResponse DTOs, so the API
 * never leaks the full User (passwordHash, tokenVersion, role) or Project the entity holds.
 * ChargeReelleMapper does the same job for the actual-worked side; that one has two extra fields
 * for the submit/validate trail, since a plan has no approval cycle.
 */

/**
 * Entity-to-DTO rules for the planned workload (plan de charge). MapStruct fails the build if a
 * response field is left unmapped, unlike a hand-written mapper that would silently send null.
 */
// componentModel = "spring" registers the generated impl as a Spring bean for constructor injection.
@Mapper(componentModel = "spring")
public interface PlanChargeMapper {

    // Entity -> DTO; null in, null out. year/month are split from the single `period` date. Needs the
    // repository's JOIN FETCH (project, user) to avoid N+1 queries on projectCode/userFullName.
    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user", qualifiedByName = "fullName")
    @Mapping(target = "year",         expression = "java(planCharge.getPeriod().getYear())")
    @Mapping(target = "month",        expression = "java(planCharge.getPeriod().getMonthValue())")
    PlanChargeResponse toResponse(PlanCharge planCharge);

    // Maps a list in one pass, reusing the rules above; order comes from the repository's ORDER BY.
    List<PlanChargeResponse> toResponseList(List<PlanCharge> list);

    /** Null-safe "First Last" via User.getFullName(), the single source of truth for name formatting. */
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

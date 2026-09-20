package com.pms.workload.mapper;

import com.pms.user.entity.User;
import com.pms.workload.dto.ChargeReelleResponse;
import com.pms.workload.entity.ChargeReelle;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/*
 * MapStruct rules translating ChargeReelle entities to flat ChargeReelleResponse DTOs, so the API
 * never leaks the full User (passwordHash, tokenVersion, role) or Project the entity holds.
 * PlanChargeMapper does the same job for the forecast side; this one has two extra fields for the
 * submit/validate trail.
 */

/**
 * Entity-to-DTO rules for the actual workload (charges reelles). MapStruct fails the build if a
 * response field is left unmapped, unlike a hand-written mapper that would silently send null.
 */
// componentModel = "spring" registers the generated impl as a Spring bean for constructor injection.
@Mapper(componentModel = "spring")
public interface ChargeReelleMapper {

    /*
     * Entity -> DTO; null in, null out. year/month are split from the single `period` date.
     * validatedById/validatedByName go through null-safe helpers since validatedBy is null until
     * approved. Needs the repository's JOIN FETCH (project, user) and LEFT JOIN FETCH (validatedBy)
     * to avoid N+1 queries — LEFT so charges still awaiting approval aren't dropped from the list.
     */
    @Mapping(target = "projectId",      source = "project.id")
    @Mapping(target = "projectCode",    source = "project.code")
    @Mapping(target = "userId",         source = "user.id")
    @Mapping(target = "userFullName",   source = "user",        qualifiedByName = "fullName")
    @Mapping(target = "year",           expression = "java(chargeReelle.getPeriod().getYear())")
    @Mapping(target = "month",          expression = "java(chargeReelle.getPeriod().getMonthValue())")
    @Mapping(target = "validatedById",  source = "validatedBy", qualifiedByName = "userId")
    @Mapping(target = "validatedByName",source = "validatedBy", qualifiedByName = "fullName")
    ChargeReelleResponse toResponse(ChargeReelle chargeReelle);

    // Maps a list in one pass, reusing the rules above; order comes from the repository's ORDER BY.
    List<ChargeReelleResponse> toResponseList(List<ChargeReelle> list);

    /** Null-safe "First Last" via User.getFullName(), the single source of truth for name formatting. */
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }

    /** Null-safe id extraction, used for validatedBy which is null until a charge is approved. */
    @Named("userId")
    default Long userId(User user) {
        return user != null ? user.getId() : null;
    }
}

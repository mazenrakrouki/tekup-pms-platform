package com.pms.agile.mapper;

import com.pms.agile.dto.SprintResponse;
import com.pms.agile.entity.Sprint;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * MapStruct translation from the Sprint entity to the flat SprintResponse sent to the browser.
 * Avoids returning the entity directly, which would leak the linked Project (budget, margin,
 * client) and would throw LazyInitializationException once Jackson touches it post-transaction.
 * No toEntity(SprintRequest): SprintService builds the entity by hand because creation also
 * needs validateDates() and the project comes from the URL, not the body — a generated reverse
 * mapper would skip both.
 */
// componentModel = "spring" so the generated SprintMapperImpl is a Spring bean that
// SprintService can receive through constructor injection.
@Mapper(componentModel = "spring")
public interface SprintMapper {

    /**
     * Flattens one sprint. id/name/goal/startDate/endDate/status copy by name; only the two
     * values living inside the linked Project need a rule.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    SprintResponse toResponse(Sprint sprint);

    /** Maps a whole project's sprints, preserving the repository's startDate order. */
    List<SprintResponse> toResponseList(List<Sprint> sprints);
}

package com.pms.mission.mapper;

import com.pms.mission.dto.MissionResponse;
import com.pms.mission.entity.Mission;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/**
 * Translates Mission entities into flat MissionResponse records for JSON. Without it, the
 * controller would have to return the entity itself, leaking the whole Project and User objects
 * (including the password hash) and risking LazyInitializationException on their lazy fields.
 * Generated at compile time by MapStruct (ADR-018), so an unmapped field fails the build instead
 * of silently sending null. Sister of ComposanteMapper, which maps this mission's cost lines;
 * the two are linked by MissionResponse.id / ComposanteResponse.missionId.
 */
@Mapper(componentModel = "spring")
public interface MissionMapper {

    // @Mapper(componentModel = "spring") registers the generated impl as a Spring bean;
    // without it MissionService can't get one injected and startup fails. Fields objet, lieu,
    // dateDebut, dateFin and id copy automatically by matching names. The four mismatches below
    // are mapped explicitly, each with a MapStruct null-guard for the nested read:
    //  - projectId / projectCode: from project.id / project.code, so the flat id and the
    //    human-readable code travel without the nested Project object.
    //  - userId: from user.id, so the edit form can preselect the traveller.
    //  - userFullName: built by the fullName() helper below rather than a direct field, since
    //    it joins two columns into one display string.
    // Both parents are loaded via JOIN FETCH in MissionRepository, so reading their fields here
    // costs no extra query (avoiding the classic N+1 problem).
    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user", qualifiedByName = "fullName")
    MissionResponse toResponse(Mission mission);

    // Maps a whole list, reusing the same @Mapping rules as toResponse() so the two can never
    // drift apart. Order is preserved from the repository query (ORDER BY m.dateDebut).
    List<MissionResponse> toResponseList(List<Mission> missions);

    // Builds the traveller's display name, returning null instead of throwing when the user row
    // is missing. @Named("fullName") lets the @Mapping above select this helper explicitly,
    // rather than it being picked up as a generic User -> String conversion elsewhere.
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

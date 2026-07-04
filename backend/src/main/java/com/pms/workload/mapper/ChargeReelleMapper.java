package com.pms.workload.mapper;

import com.pms.user.entity.User;
import com.pms.workload.dto.ChargeReelleResponse;
import com.pms.workload.entity.ChargeReelle;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ChargeReelleMapper {

    @Mapping(target = "projectId",      source = "project.id")
    @Mapping(target = "projectCode",    source = "project.code")
    @Mapping(target = "userId",         source = "user.id")
    @Mapping(target = "userFullName",   source = "user",        qualifiedByName = "fullName")
    @Mapping(target = "year",           expression = "java(chargeReelle.getPeriod().getYear())")
    @Mapping(target = "month",          expression = "java(chargeReelle.getPeriod().getMonthValue())")
    @Mapping(target = "validatedById",  source = "validatedBy", qualifiedByName = "userId")
    @Mapping(target = "validatedByName",source = "validatedBy", qualifiedByName = "fullName")
    ChargeReelleResponse toResponse(ChargeReelle chargeReelle);

    List<ChargeReelleResponse> toResponseList(List<ChargeReelle> list);

    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }

    @Named("userId")
    default Long userId(User user) {
        return user != null ? user.getId() : null;
    }
}

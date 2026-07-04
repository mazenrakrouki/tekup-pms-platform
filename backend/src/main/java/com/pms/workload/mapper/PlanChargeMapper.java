package com.pms.workload.mapper;

import com.pms.user.entity.User;
import com.pms.workload.dto.PlanChargeResponse;
import com.pms.workload.entity.PlanCharge;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface PlanChargeMapper {

    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user", qualifiedByName = "fullName")
    @Mapping(target = "year",         expression = "java(planCharge.getPeriod().getYear())")
    @Mapping(target = "month",        expression = "java(planCharge.getPeriod().getMonthValue())")
    PlanChargeResponse toResponse(PlanCharge planCharge);

    List<PlanChargeResponse> toResponseList(List<PlanCharge> list);

    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

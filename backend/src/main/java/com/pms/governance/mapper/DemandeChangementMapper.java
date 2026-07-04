package com.pms.governance.mapper;

import com.pms.governance.dto.DemandeChangementResponse;
import com.pms.governance.entity.DemandeChangement;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface DemandeChangementMapper {

    @Mapping(target = "projectId",         source = "project.id")
    @Mapping(target = "projectCode",       source = "project.code")
    @Mapping(target = "demandeurId",       source = "demandeur.id")
    @Mapping(target = "demandeurFullName", source = "demandeur", qualifiedByName = "fullName")
    DemandeChangementResponse toResponse(DemandeChangement dc);

    List<DemandeChangementResponse> toResponseList(List<DemandeChangement> list);

    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

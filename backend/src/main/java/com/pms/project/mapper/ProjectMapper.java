package com.pms.project.mapper;

import com.pms.project.dto.ProjectResponse;
import com.pms.project.entity.Project;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ProjectMapper {

    @Mapping(target = "directorId",    source = "director",   qualifiedByName = "userId")
    @Mapping(target = "directorName",  source = "director",   qualifiedByName = "fullName")
    @Mapping(target = "chefProjetId",  source = "chefProjet", qualifiedByName = "userId")
    @Mapping(target = "chefProjetName",source = "chefProjet", qualifiedByName = "fullName")
    @Mapping(target = "effectiveBudget", expression = "java(project.getEffectiveBudget())")
    ProjectResponse toResponse(Project project);

    List<ProjectResponse> toResponseList(List<Project> projects);

    @Named("userId")
    default Long userId(User user) {
        return user != null ? user.getId() : null;
    }

    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

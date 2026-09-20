package com.pms.project.mapper;

import com.pms.project.dto.ProjectResponse;
import com.pms.project.entity.Project;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

// Translator between the Project entity and the ProjectResponse DTO. Exists mainly for safety:
// returning the entity as-is would serialize the full director/chefProjet User objects — email,
// bcrypt hash, tokenVersion, role — since both are @ManyToOne(LAZY) and would also risk
// LazyInitializationException outside a transaction. MapStruct (ADR-018) generates the real
// implementation at compile time, so a DTO field left unmapped fails the build instead of
// silently serializing as null. It never applies BR-050 redaction or permission/scope checks —
// those happen one level up, in ProjectService.
@Mapper(componentModel = "spring")
public interface ProjectMapper {

    /*
     * Builds a new ProjectResponse from a Project row; never modifies the entity.
     * Most fields copy automatically because the names match on both sides. durationDays,
     * budgetTnd and pprTnd need no @Mapping either: MapStruct matches a target name against a
     * getter, so Project.getDurationDays()/getBudgetTnd()/getPprTnd() are found by name alone.
     * status/businessModel/engagementType are enums on both sides and copy as-is.
     *
     * The five @Mapping lines below handle what can't be matched by name:
     *  - directorId/directorName and chefProjetId/chefProjetName: the source is the whole User
     *    object, so qualifiedByName routes it through the userId/fullName helpers at the bottom
     *    to flatten it — sending the User object itself would leak its email and password hash.
     *  - effectiveBudget: computed via project.getEffectiveBudget() (revisedBudget when set,
     *    otherwise initialBudget), spelled out here rather than left to name-matching so the
     *    intent survives a later rename.
     *
     * Both director and chefProjet are lazy, so ProjectRepository's queries all use
     * "LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet" to avoid firing an extra SELECT
     * per user per row (N+1) — required here since open-in-view is false and a lazy field can't
     * be touched once the transaction has closed.
     */
    @Mapping(target = "directorId",    source = "director",   qualifiedByName = "userId")
    @Mapping(target = "directorName",  source = "director",   qualifiedByName = "fullName")
    @Mapping(target = "chefProjetId",  source = "chefProjet", qualifiedByName = "userId")
    @Mapping(target = "chefProjetName",source = "chefProjet", qualifiedByName = "fullName")
    @Mapping(target = "effectiveBudget", expression = "java(project.getEffectiveBudget())")
    ProjectResponse toResponse(Project project);

    // Maps a whole list, reusing the same @Mapping rules above. Not currently wired into the
    // project endpoints: ProjectService keeps its own private toResponseList()/toResponse(),
    // which apply BR-050 (blanking financial fields without VIEW_KPI) — calling this method
    // directly would skip that redaction.
    List<ProjectResponse> toResponseList(List<Project> projects);

    // Helper for rules (1)/(3) above: primary key of a User, or null. Named so a @Mapping can
    // call it explicitly instead of MapStruct guessing among multiple User->Long methods.
    @Named("userId")
    default Long userId(User user) {
        return user != null ? user.getId() : null;
    }

    // Helper for rules (2)/(4) above: display name of a User, or null (a project can have no
    // chef de projet assigned yet).
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}

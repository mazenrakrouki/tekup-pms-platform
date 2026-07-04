package com.pms;

import com.pms.project.entity.Project;
import com.pms.project.entity.ProjectStatus;
import com.pms.project.repository.ProjectRepository;
import com.pms.user.entity.Permission;
import com.pms.user.entity.Role;
import com.pms.user.entity.User;
import com.pms.user.repository.PermissionRepository;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

public final class TestFixtures {

    private TestFixtures() {}

    /**
     * Super-admin de test avec TOUTES les permissions.
     * Le Set est mutable (HashSet) — les tests peuvent y ajouter des permissions si besoin.
     * Identifiants : admin-test@pms.local / Admin1234!
     */
    public static User createAdminUser(PermissionRepository permRepo,
                                       RoleRepository roleRepo,
                                       UserRepository userRepo,
                                       PasswordEncoder encoder) {
        Set<Permission> permissions = new HashSet<>();

        // Administration
        permissions.add(perm(permRepo, "MANAGE_USERS",    "ADMIN"));
        permissions.add(perm(permRepo, "MANAGE_ROLES",    "ADMIN"));
        permissions.add(perm(permRepo, "VIEW_AUDIT_LOG",  "ADMIN"));
        // Projets
        permissions.add(perm(permRepo, "VIEW_PROJECT",       "PROJET"));
        permissions.add(perm(permRepo, "VIEW_ALL_PROJECTS",  "PROJET")); // accès portefeuille (scope ADR-021)
        permissions.add(perm(permRepo, "CREATE_PROJECT",     "PROJET"));
        permissions.add(perm(permRepo, "EDIT_PROJECT",       "PROJET"));
        permissions.add(perm(permRepo, "DELETE_PROJECT",     "PROJET"));
        permissions.add(perm(permRepo, "ASSIGN_CHEF_PROJET", "PROJET"));
        // Équipes
        permissions.add(perm(permRepo, "ASSIGN_DEVELOPER", "EQUIPE"));
        permissions.add(perm(permRepo, "VIEW_TEAM",         "EQUIPE"));
        // Charges
        permissions.add(perm(permRepo, "SUBMIT_WORKLOAD",   "CHARGE"));
        permissions.add(perm(permRepo, "VALIDATE_WORKLOAD", "CHARGE"));
        permissions.add(perm(permRepo, "VIEW_WORKLOAD",     "CHARGE"));
        // Facturation
        permissions.add(perm(permRepo, "MANAGE_BILLING",   "FACTURATION"));
        permissions.add(perm(permRepo, "VIEW_BILLING",     "FACTURATION"));
        // KPI
        permissions.add(perm(permRepo, "VIEW_KPI",         "KPI"));
        // Ressources
        permissions.add(perm(permRepo, "MANAGE_RESOURCES", "RESSOURCE"));
        permissions.add(perm(permRepo, "VIEW_RESOURCES",   "RESSOURCE"));
        // Gouvernance
        permissions.add(perm(permRepo, "MANAGE_GOVERNANCE","GOV"));
        permissions.add(perm(permRepo, "VIEW_GOVERNANCE",  "GOV"));

        Role admin = roleRepo.save(Role.builder()
                .name("ADMIN_TEST")
                .permissions(permissions)
                .build());

        return userRepo.save(User.builder()
                .firstName("Admin").lastName("Test")
                .email("admin-test@pms.local")
                .passwordHash(encoder.encode("Admin1234!"))
                .active(true).firstLogin(false).role(admin)
                .build());
    }

    /**
     * Utilisateur en lecture seule — VIEW_* uniquement.
     * Prouve que ADR-001 (@PreAuthorize) bloque les opérations d'écriture.
     * Identifiants : viewer-test@pms.local / Viewer1234!
     */
    public static User createViewerUser(PermissionRepository permRepo,
                                        RoleRepository roleRepo,
                                        UserRepository userRepo,
                                        PasswordEncoder encoder) {
        Set<Permission> permissions = new HashSet<>();
        permissions.add(perm(permRepo, "VIEW_PROJECT",    "PROJET"));
        permissions.add(perm(permRepo, "VIEW_BILLING",    "FACTURATION"));
        permissions.add(perm(permRepo, "VIEW_GOVERNANCE", "GOV"));
        permissions.add(perm(permRepo, "VIEW_TEAM",       "EQUIPE"));
        permissions.add(perm(permRepo, "VIEW_WORKLOAD",   "CHARGE"));
        permissions.add(perm(permRepo, "VIEW_KPI",        "KPI"));

        Role viewer = roleRepo.save(Role.builder()
                .name("VIEWER_TEST")
                .permissions(permissions)
                .build());

        return userRepo.save(User.builder()
                .firstName("Viewer").lastName("Test")
                .email("viewer-test@pms.local")
                .passwordHash(encoder.encode("Viewer1234!"))
                .active(true).firstLogin(false).role(viewer)
                .build());
    }

    public static Project createProject(ProjectRepository projectRepo) {
        return projectRepo.save(Project.builder()
                .code("TEST-01")
                .name("Projet Test")
                .status(ProjectStatus.ACTIVE)
                .initialBudget(BigDecimal.valueOf(100_000))
                .build());
    }

    public static String extractToken(String responseBody) {
        int start = responseBody.indexOf("\"accessToken\":\"") + 15;
        int end   = responseBody.indexOf("\"", start);
        return responseBody.substring(start, end);
    }

    /** Récupère par code ou crée — idempotent (code est UNIQUE). */
    private static Permission perm(PermissionRepository repo, String code, String module) {
        return repo.findByCode(code)
                .orElseGet(() -> repo.save(Permission.builder().code(code).module(module).build()));
    }
}

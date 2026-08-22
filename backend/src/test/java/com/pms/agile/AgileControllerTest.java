package com.pms.agile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.TestFixtures;
import com.pms.agile.entity.BacklogItemStatus;
import com.pms.agile.entity.BacklogPriority;
import com.pms.agile.entity.SprintStatus;
import com.pms.auth.dto.LoginRequest;
import com.pms.project.entity.Project;
import com.pms.project.entity.ProjectStatus;
import com.pms.project.repository.ProjectRepository;
import com.pms.user.entity.Permission;
import com.pms.user.entity.Role;
import com.pms.user.entity.User;
import com.pms.user.repository.PermissionRepository;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgileControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String managerToken;   // MANAGE_AGILE + VIEW_AGILE
    private String viewerToken;    // VIEW_AGILE seulement
    private Long   projectId;
    private Long   otherProjectId;

    @BeforeEach
    void setUp() throws Exception {
        TestFixtures.createAdminUser(permissionRepository, roleRepository, userRepository, passwordEncoder);
        TestFixtures.createViewerUser(permissionRepository, roleRepository, userRepository, passwordEncoder);

        projectId = TestFixtures.createProject(projectRepository).getId();

        // Second projet : support des contrôles de cloisonnement inter-projets.
        otherProjectId = projectRepository.save(Project.builder()
                .code("TEST-02")
                .name("Autre Projet")
                .status(ProjectStatus.ACTIVE)
                .initialBudget(BigDecimal.valueOf(50_000))
                .build()).getId();

        managerToken = login("admin-test@pms.local", "Admin1234!");
        viewerToken  = login("viewer-test@pms.local", "Viewer1234!");
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andReturn();
        return TestFixtures.extractToken(result.getResponse().getContentAsString());
    }

    /**
     * Crée un utilisateur portant exactement les capacités demandées.
     *
     * Nécessaire parce que l'accès aux routes {@code /api/projects/{id}/**} exige
     * <b>capacité ∧ périmètre</b> (ADR-021, {@code ProjectScopeInterceptor}) : un lecteur
     * sans {@code VIEW_ALL_PROJECTS} ni affectation au projet est refusé avant même que
     * {@code @PreAuthorize} ne soit évalué. Pour isoler le contrôle de capacité, il faut donc
     * un utilisateur qui soit déjà dans le périmètre.
     */
    private String userWithPermissions(String roleName, String email, String... codes) throws Exception {
        Set<Permission> permissions = new HashSet<>();
        for (String code : codes) {
            permissions.add(permissionRepository.findByCode(code)
                    .orElseGet(() -> permissionRepository.save(
                            Permission.builder().code(code).module("TEST").build())));
        }
        Role role = roleRepository.save(Role.builder().name(roleName).permissions(permissions).build());
        userRepository.save(User.builder()
                .firstName("Test").lastName(roleName)
                .email(email)
                .passwordHash(passwordEncoder.encode("Test1234!"))
                .active(true).firstLogin(false).role(role)
                .build());
        return login(email, "Test1234!");
    }

    private Map<String, Object> sprintBody(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("goal", "Objectif de l'itération");
        body.put("startDate", "2026-03-02");
        body.put("endDate", "2026-03-13");
        body.put("status", SprintStatus.PLANNED.name());
        return body;
    }

    private Long createSprint(Long inProject, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects/{p}/sprints", inProject)
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sprintBody(name))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    // ── Autorisation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /sprints avec MANAGE_AGILE → 201")
    void createSprint_asManager_returns201() throws Exception {
        mockMvc.perform(post("/api/projects/{p}/sprints", projectId)
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sprintBody("Sprint 1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sprint 1"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.projectId").value(projectId));
    }

    @Test
    @DisplayName("Périmètre (ADR-021) : VIEW_AGILE sans accès au projet → 403")
    void outOfScopeReader_isDenied() throws Exception {
        // Le viewer détient VIEW_AGILE mais n'est ni chef de projet ni affecté : le module
        // hérite du cloisonnement de données appliqué à toutes les routes /api/projects/{id}/**.
        mockMvc.perform(get("/api/projects/{p}/sprints", projectId)
                .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Capacité (ADR-001) : lecteur dans le périmètre lit mais n'écrit pas")
    void inScopeReader_canRead_cannotWrite() throws Exception {
        String readerToken = userWithPermissions(
                "AGILE_READER_TEST", "agile-reader@pms.local", "VIEW_AGILE", "VIEW_ALL_PROJECTS");

        mockMvc.perform(get("/api/projects/{p}/sprints", projectId)
                .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk());

        // Même utilisateur, même projet : seule l'absence de MANAGE_AGILE motive le refus.
        mockMvc.perform(post("/api/projects/{p}/sprints", projectId)
                .header("Authorization", "Bearer " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sprintBody("Interdit"))))
                .andExpect(status().isForbidden());
    }

    // ── Cloisonnement inter-projets ────────────────────────────────────────

    @Test
    @DisplayName("Un sprint n'est pas accessible via l'URL d'un autre projet → 404")
    void sprint_fromAnotherProject_returns404() throws Exception {
        Long sprintId = createSprint(projectId, "Sprint 1");

        mockMvc.perform(put("/api/projects/{p}/sprints/{id}", otherProjectId, sprintId)
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sprintBody("Détourné"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/projects/{p}/sprints/{id}", otherProjectId, sprintId)
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Un élément ne peut pas être rattaché au sprint d'un autre projet → 404")
    void backlogItem_cannotJoinForeignSprint() throws Exception {
        Long foreignSprintId = createSprint(otherProjectId, "Sprint distant");

        Map<String, Object> body = new HashMap<>();
        body.put("title", "Élément frontalier");
        body.put("priority", BacklogPriority.MEDIUM.name());
        body.put("status", BacklogItemStatus.TODO.name());
        body.put("sprintId", foreignSprintId);

        mockMvc.perform(post("/api/projects/{p}/backlog", projectId)
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    // ── Règles métier ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Date de fin antérieure à la date de début → 422")
    void createSprint_endBeforeStart_returns422() throws Exception {
        Map<String, Object> body = sprintBody("Sprint incohérent");
        body.put("startDate", "2026-03-13");
        body.put("endDate", "2026-03-02");

        mockMvc.perform(post("/api/projects/{p}/sprints", projectId)
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Supprimer un sprint renvoie ses éléments au backlog produit, sans les perdre")
    void deleteSprint_returnsItemsToProductBacklog() throws Exception {
        Long sprintId = createSprint(projectId, "Sprint 1");

        Map<String, Object> item = new HashMap<>();
        item.put("title", "Élément engagé");
        item.put("priority", BacklogPriority.HIGH.name());
        item.put("status", BacklogItemStatus.TODO.name());
        item.put("estimateDays", 2.5);
        item.put("sprintId", sprintId);

        mockMvc.perform(post("/api/projects/{p}/backlog", projectId)
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(item)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sprintId").value(sprintId));

        mockMvc.perform(delete("/api/projects/{p}/sprints/{id}", projectId, sprintId)
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNoContent());

        // L'élément existe toujours et son sprint est retombé à null.
        mockMvc.perform(get("/api/projects/{p}/backlog", projectId)
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Élément engagé"))
                .andExpect(jsonPath("$[0].sprintId").doesNotExist());
    }

    // ── Tableau ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /backlog/{id}/move déplace la carte de colonne")
    void moveBacklogItem_changesColumn() throws Exception {
        Long sprintId = createSprint(projectId, "Sprint 1");

        Map<String, Object> item = new HashMap<>();
        item.put("title", "Carte à déplacer");
        item.put("priority", BacklogPriority.MEDIUM.name());
        item.put("status", BacklogItemStatus.TODO.name());
        item.put("sprintId", sprintId);

        MvcResult created = mockMvc.perform(post("/api/projects/{p}/backlog", projectId)
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(item)))
                .andExpect(status().isCreated())
                .andReturn();
        Long itemId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        Map<String, Object> move = new HashMap<>();
        move.put("status", BacklogItemStatus.IN_PROGRESS.name());
        move.put("sprintId", sprintId);

        mockMvc.perform(patch("/api/projects/{p}/backlog/{id}/move", projectId, itemId)
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(move)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }
}

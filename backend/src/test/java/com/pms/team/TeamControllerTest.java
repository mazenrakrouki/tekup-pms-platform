package com.pms.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.TestFixtures;
import com.pms.auth.dto.LoginRequest;
import com.pms.project.repository.ProjectRepository;
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

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeamControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String token;
    private Long projectId;
    private Long memberId;

    @BeforeEach
    void setUp() throws Exception {
        TestFixtures.createAdminUser(permissionRepository, roleRepository, userRepository, passwordEncoder);
        projectId = TestFixtures.createProject(projectRepository).getId();

        // Crée un utilisateur à affecter dans l'équipe (rôle minimal, juste pour être la cible)
        Role devRole = roleRepository.save(Role.builder()
                .name("DEV_TEST")
                .build());
        User member = userRepository.save(User.builder()
                .firstName("Dev").lastName("Member")
                .email("dev-test@pms.local")
                .passwordHash(passwordEncoder.encode("Dev1234!"))
                .active(true).firstLogin(false).role(devRole)
                .build());
        memberId = member.getId();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new LoginRequest("admin-test@pms.local", "Admin1234!"))))
                .andReturn();
        token = TestFixtures.extractToken(login.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("GET /team sans token → 401")
    void listTeam_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/team"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /team projet vide → 200 + liste vide")
    void listTeam_emptyTeam_returns200() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/team")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /team avec ASSIGN_DEVELOPER → 201 + membre affecté")
    void assignMember_validRequest_returns201() throws Exception {
        var body = Map.of(
                "userId", memberId,
                "roleInTeam", "Développeur Backend",
                "startDate", "2026-07-01"
        );

        mockMvc.perform(post("/api/projects/" + projectId + "/team")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.roleInTeam").value("Développeur Backend"));
    }

    @Test
    @DisplayName("POST /team même membre deux fois → 409 (doublon actif)")
    void assignMember_duplicate_returns409() throws Exception {
        var body = Map.of(
                "userId", memberId,
                "roleInTeam", "Dev",
                "startDate", "2026-07-01"
        );

        mockMvc.perform(post("/api/projects/" + projectId + "/team")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // Deuxième affectation du même utilisateur (sans fin de la première) → 409
        mockMvc.perform(post("/api/projects/" + projectId + "/team")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("DELETE /team/{id} → 204 + membre retiré (soft-delete)")
    void removeMember_existing_returns204() throws Exception {
        var body = Map.of("userId", memberId, "roleInTeam", "Dev", "startDate", "2026-07-01");
        MvcResult create = mockMvc.perform(post("/api/projects/" + projectId + "/team")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        Long assignmentId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(delete("/api/projects/" + projectId + "/team/" + assignmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /users/{userId}/assignments → 200 + historique affectations")
    void getUserAssignments_returns200() throws Exception {
        mockMvc.perform(get("/api/users/" + memberId + "/assignments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}

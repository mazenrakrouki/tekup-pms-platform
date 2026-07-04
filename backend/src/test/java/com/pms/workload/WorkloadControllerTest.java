package com.pms.workload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.TestFixtures;
import com.pms.auth.dto.LoginRequest;
import com.pms.project.repository.ProjectRepository;
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
class WorkloadControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String token;
    private Long projectId;
    private Long targetUserId;

    @BeforeEach
    void setUp() throws Exception {
        User admin = TestFixtures.createAdminUser(permissionRepository, roleRepository, userRepository, passwordEncoder);
        projectId = TestFixtures.createProject(projectRepository).getId();
        // L'admin lui-même servira de cible pour les charges (userId)
        targetUserId = admin.getId();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new LoginRequest("admin-test@pms.local", "Admin1234!"))))
                .andReturn();
        token = TestFixtures.extractToken(login.getResponse().getContentAsString());
    }

    // ── Plan Charges ───────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /plan-charges sans token → 401")
    void listPlanCharges_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/plan-charges"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /plan-charges → 201, plannedDays enregistré")
    void createPlanCharge_validRequest_returns201() throws Exception {
        var body = Map.of(
                "userId", targetUserId,
                "year", 2026,
                "month", 7,
                "plannedDays", "15"
        );

        mockMvc.perform(post("/api/projects/" + projectId + "/plan-charges")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.plannedDays").value(15.0));
    }

    @Test
    @DisplayName("POST /plan-charges doublon (même userId/year/month) → 409")
    void createPlanCharge_duplicate_returns409() throws Exception {
        var body = Map.of("userId", targetUserId, "year", 2026, "month", 8, "plannedDays", "10");

        mockMvc.perform(post("/api/projects/" + projectId + "/plan-charges")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/projects/" + projectId + "/plan-charges")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("DELETE /plan-charges/{id} → 204")
    void deletePlanCharge_existing_returns204() throws Exception {
        var body = Map.of("userId", targetUserId, "year", 2026, "month", 9, "plannedDays", "5");
        MvcResult create = mockMvc.perform(post("/api/projects/" + projectId + "/plan-charges")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        Long id = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/api/projects/" + projectId + "/plan-charges/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    // ── Charges Réelles ────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /charges-reelles → 201, actualDays enregistré")
    void submitChargeReelle_validRequest_returns201() throws Exception {
        var body = Map.of(
                "userId", targetUserId,
                "year", 2026,
                "month", 7,
                "actualDays", "12"
        );

        mockMvc.perform(post("/api/projects/" + projectId + "/charges-reelles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actualDays").value(12.0));
    }

    @Test
    @DisplayName("PATCH /charges-reelles/{id}/validate → charge validée")
    void validateChargeReelle_returns200() throws Exception {
        var body = Map.of("userId", targetUserId, "year", 2026, "month", 10, "actualDays", "8");
        MvcResult create = mockMvc.perform(post("/api/projects/" + projectId + "/charges-reelles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        Long id = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/api/projects/" + projectId + "/charges-reelles/" + id + "/validate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /validate sur charge déjà validée → 409")
    void validateChargeReelle_alreadyValidated_returns409() throws Exception {
        var body = Map.of("userId", targetUserId, "year", 2026, "month", 11, "actualDays", "6");
        MvcResult create = mockMvc.perform(post("/api/projects/" + projectId + "/charges-reelles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        Long id = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asLong();
        String base = "/api/projects/" + projectId + "/charges-reelles/" + id;

        mockMvc.perform(patch(base + "/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(patch(base + "/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }
}

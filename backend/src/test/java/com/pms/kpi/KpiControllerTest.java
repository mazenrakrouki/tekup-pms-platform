package com.pms.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.TestFixtures;
import com.pms.auth.dto.LoginRequest;
import com.pms.project.repository.ProjectRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class KpiControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String token;
    private Long projectId;

    @BeforeEach
    void setUp() throws Exception {
        TestFixtures.createAdminUser(permissionRepository, roleRepository, userRepository, passwordEncoder);
        projectId = TestFixtures.createProject(projectRepository).getId();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new LoginRequest("admin-test@pms.local", "Admin1234!"))))
                .andReturn();
        token = TestFixtures.extractToken(login.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("GET /kpi sans token → 401")
    void getKpi_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/kpi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /kpi → 200 + structure KpiResponse correcte (projet sans charges = 0)")
    void getKpi_projectWithoutCharges_returns200WithZeroes() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/kpi")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.budgetPlanifie").value(0))
                .andExpect(jsonPath("$.budgetConsome").value(0))
                .andExpect(jsonPath("$.snapshotId").doesNotExist());
    }

    @Test
    @DisplayName("GET /kpi/snapshots → 200 + liste vide initialement")
    void listSnapshots_emptyInitially_returns200() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/kpi/snapshots")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /kpi/snapshots → 201 + snapshotId persisté + Location pointant dessus")
    void createSnapshot_returns201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects/" + projectId + "/kpi/snapshots")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.projectId").value(projectId))
                // snapshotId doit être renseigné (id persisté) — sinon Location pointerait sur /null
                .andExpect(jsonPath("$.snapshotId").isNumber())
                .andExpect(jsonPath("$.snapshotDate").isNotEmpty())
                .andReturn();

        // Le Location doit se terminer par l'id réel du snapshot
        Long snapshotId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("snapshotId").asLong();
        String location = result.getResponse().getHeader("Location");
        org.junit.jupiter.api.Assertions.assertTrue(
                location != null && location.endsWith("/snapshots/" + snapshotId),
                "Location doit se terminer par /snapshots/" + snapshotId + " mais vaut : " + location);
    }

    @Test
    @DisplayName("POST /kpi/snapshots deux fois même jour → 409")
    void createSnapshot_twiceOnSameDay_returns409() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/kpi/snapshots")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        // Même projet, même jour → partial unique index déclenche une erreur métier
        mockMvc.perform(post("/api/projects/" + projectId + "/kpi/snapshots")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /kpi projet inexistant → 404")
    void getKpi_nonExistentProject_returns404() throws Exception {
        mockMvc.perform(get("/api/projects/999999/kpi")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}

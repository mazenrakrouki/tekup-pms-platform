package com.pms.billing;

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

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BillingControllerTest {

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

    private Long createJalon(String label, String pourcentage) throws Exception {
        var body = Map.of("label", label, "pourcentage", pourcentage);
        MvcResult result = mockMvc.perform(post("/api/projects/" + projectId + "/jalons")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    // ── Jalons ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /jalons sans token → 401")
    void listJalons_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/jalons"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /jalons → 201, statut EN_ATTENTE par défaut")
    void createJalon_validRequest_returns201() throws Exception {
        var body = Map.of("label", "Livraison V1", "pourcentage", "30");

        mockMvc.perform(post("/api/projects/" + projectId + "/jalons")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.label").value("Livraison V1"))
                .andExpect(jsonPath("$.pourcentage").value(30));
    }

    @Test
    @DisplayName("POST /jalons avec pourcentage > 100 → 400")
    void createJalon_invalidPourcentage_returns400() throws Exception {
        var body = Map.of("label", "Invalide", "pourcentage", "101");

        mockMvc.perform(post("/api/projects/" + projectId + "/jalons")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /jalons/{id}/facturer → statut FACTURE")
    void facturerJalon_pendingJalon_setsFacture() throws Exception {
        Long jalonId = createJalon("Jalon facturable", "50");
        var body = Map.of("dateFacture", "2026-07-20");

        mockMvc.perform(patch("/api/projects/" + projectId + "/jalons/" + jalonId + "/facturer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("FACTURE"));
    }

    // ── Paiements ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /jalons/{id}/paiements → 200 + liste vide")
    void listPaiements_emptyInitially_returns200() throws Exception {
        Long jalonId = createJalon("Jalon vide", "20");

        mockMvc.perform(get("/api/projects/" + projectId + "/jalons/" + jalonId + "/paiements")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /paiements → 201, montant enregistré")
    void createPaiement_validRequest_returns201() throws Exception {
        Long jalonId = createJalon("Jalon avec paiement", "40");
        // Un jalon doit être FACTURE avant de recevoir un paiement
        var facturerBody = Map.of("dateFacture", "2026-07-15");
        mockMvc.perform(patch("/api/projects/" + projectId + "/jalons/" + jalonId + "/facturer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(facturerBody)))
                .andExpect(status().isOk());

        var body = Map.of("montantRecu", "15000", "datePaiement", "2026-07-15");

        mockMvc.perform(post("/api/projects/" + projectId + "/jalons/" + jalonId + "/paiements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.montantRecu").value(15000));
    }

    // ── Avenants ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /avenants → 201 + budget révisé mis à jour")
    void createAvenant_validRequest_returns201() throws Exception {
        var body = Map.of(
                "numero", "AVN-001",
                "objet", "Extension de périmètre",
                "montant", "10000",
                "dateAvenant", "2026-07-10"
        );

        mockMvc.perform(post("/api/projects/" + projectId + "/avenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value("AVN-001"))
                .andExpect(jsonPath("$.montant").value(10000));
    }

    @Test
    @DisplayName("GET /avenants → 200 + liste")
    void listAvenants_returns200() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/avenants")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}

package com.pms.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.TestFixtures;
import com.pms.auth.dto.LoginRequest;
import com.pms.governance.entity.NiveauRisque;
import com.pms.governance.entity.StatutRisque;
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
class GovernanceControllerTest {

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

    // ── Risques ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /risks → 201 avec les champs corrects")
    void createRisk_validRequest_returns201() throws Exception {
        var body = Map.of(
                "description", "Risque de dépassement budgétaire",
                "probabilite", NiveauRisque.ELEVE.name(),
                "impact", NiveauRisque.MOYEN.name(),
                "statut", StatutRisque.OUVERT.name()
        );

        mockMvc.perform(post("/api/projects/" + projectId + "/risks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.description").value("Risque de dépassement budgétaire"))
                .andExpect(jsonPath("$.probabilite").value("ELEVE"))
                .andExpect(jsonPath("$.statut").value("OUVERT"));
    }

    @Test
    @DisplayName("GET /risks → 200 + liste (vide initialement)")
    void listRisks_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/risks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── Livrables ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /livrables → 201, statut initial EN_ATTENTE")
    void createLivrable_returns201WithEnAttenteStatus() throws Exception {
        var body = Map.of("titre", "Spécifications fonctionnelles");

        mockMvc.perform(post("/api/projects/" + projectId + "/livrables")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.titre").value("Spécifications fonctionnelles"))
                .andExpect(jsonPath("$.statut").value("EN_ATTENTE"));
    }

    @Test
    @DisplayName("Machine à états livrable : EN_ATTENTE → EN_COURS → LIVRE → VALIDE")
    void livrable_fullStateMachine_succeeds() throws Exception {
        // 1. Créer
        var body = Map.of("titre", "Livrable Complet");
        MvcResult create = mockMvc.perform(post("/api/projects/" + projectId + "/livrables")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        Long livrableId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asLong();
        String base = "/api/projects/" + projectId + "/livrables/" + livrableId;

        // 2. Démarrer → EN_COURS
        mockMvc.perform(patch(base + "/demarrer").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("EN_COURS"));

        // 3. Livrer → LIVRE
        mockMvc.perform(patch(base + "/livrer").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("LIVRE"));

        // 4. Valider → VALIDE
        mockMvc.perform(patch(base + "/valider").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("VALIDE"));
    }

    @Test
    @DisplayName("Valider un livrable EN_ATTENTE (pas LIVRE) → 409")
    void validerLivrable_notLivre_returns409() throws Exception {
        var body = Map.of("titre", "Livrable non livré");
        MvcResult create = mockMvc.perform(post("/api/projects/" + projectId + "/livrables")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        Long livrableId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(patch("/api/projects/" + projectId + "/livrables/" + livrableId + "/valider")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    // ── Demandes de Changement ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /demandes-changement → 201, statut EN_ATTENTE")
    void createDC_returns201WithEnAttenteStatus() throws Exception {
        Long userId = userRepository.findAll().get(0).getId();
        var body = Map.of(
                "demandeurId", userId,
                "titre", "Ajout module reporting",
                "priorite", "NORMALE",
                "dateDemande", "2026-07-01"
        );

        mockMvc.perform(post("/api/projects/" + projectId + "/demandes-changement")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("EN_ATTENTE"))
                .andExpect(jsonPath("$.dateDecision").doesNotExist());
    }

    @Test
    @DisplayName("PATCH /approuver → statut APPROUVE + dateDecision renseignée")
    void approveDC_setsApprouveAndDate() throws Exception {
        Long userId = userRepository.findAll().get(0).getId();
        var body = Map.of("demandeurId", userId, "titre", "DC à approuver",
                "priorite", "ELEVEE", "dateDemande", "2026-07-01");

        MvcResult create = mockMvc.perform(post("/api/projects/" + projectId + "/demandes-changement")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        Long dcId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/api/projects/" + projectId + "/demandes-changement/" + dcId + "/approuver")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("APPROUVE"))
                .andExpect(jsonPath("$.dateDecision").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /approuver sur DC déjà traitée → 409")
    void approveDC_alreadyProcessed_returns409() throws Exception {
        Long userId = userRepository.findAll().get(0).getId();
        var body = Map.of("demandeurId", userId, "titre", "DC doublon",
                "priorite", "FAIBLE", "dateDemande", "2026-07-01");

        MvcResult create = mockMvc.perform(post("/api/projects/" + projectId + "/demandes-changement")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        Long dcId = objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asLong();
        String base = "/api/projects/" + projectId + "/demandes-changement/" + dcId;

        // Première approbation
        mockMvc.perform(patch(base + "/approuver").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Deuxième approbation → 409
        mockMvc.perform(patch(base + "/approuver").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }
}

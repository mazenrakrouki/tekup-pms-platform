package com.pms.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.TestFixtures;
import com.pms.auth.dto.LoginRequest;
import com.pms.user.entity.TccAnnuel;
import com.pms.user.entity.User;
import com.pms.user.repository.PermissionRepository;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.TccAnnuelRepository;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tarifs TCC par année (F-AFF-13 §6.3 règle 4). Couvre la mise à jour d'une année
 * déjà enregistrée : l'ancienne implémentation (delete-then-reinsert) heurtait
 * l'index unique partiel à cause de l'ordre INSERT-avant-UPDATE d'Hibernate,
 * renvoyant 409 à tort. Tourne sur H2 sans Flyway (donc sans le vrai index), d'où
 * l'assertion directe sur l'invariant plutôt que sur le code HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TccAnnuelServiceTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TccAnnuelRepository tccAnnuelRepository;

    private String token;
    private Long resourceId;

    @BeforeEach
    void setUp() throws Exception {
        User admin = TestFixtures.createAdminUser(
                permissionRepository, roleRepository, userRepository, passwordEncoder);

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin-test@pms.local", "Admin1234!"))))
                .andReturn();
        token = TestFixtures.extractToken(login.getResponse().getContentAsString());

        MvcResult created = mockMvc.perform(post("/api/resources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", admin.getId(),
                                "dailyRate", "478.00",
                                "tccRate", "0.2390",
                                "staffingStart", "2022-01-01"))))
                .andExpect(status().isCreated())
                .andReturn();
        resourceId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();
    }

    private void putTcc(String json) throws Exception {
        mockMvc.perform(put("/api/resources/" + resourceId + "/tcc")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private List<TccAnnuel> activeRows() {
        return tccAnnuelRepository.findActiveByResourceId(resourceId);
    }

    @Test
    @DisplayName("Modifier une année déjà saisie met à jour sa ligne au lieu de la remplacer")
    void updatingAnExistingYearKeepsTheSameRow() throws Exception {
        putTcc("""
                [{"annee":2024,"dailyRate":478.00,"tccRate":0.2390},
                 {"annee":2025,"dailyRate":501.90,"tccRate":0.2490}]""");

        Map<Integer, Long> idsBefore = activeRows().stream()
                .collect(java.util.stream.Collectors.toMap(TccAnnuel::getAnnee, TccAnnuel::getId));
        assertThat(idsBefore).hasSize(2);

        putTcc("""
                [{"annee":2024,"dailyRate":480.00,"tccRate":0.2400},
                 {"annee":2025,"dailyRate":501.90,"tccRate":0.2490}]""");

        List<TccAnnuel> after = activeRows();
        assertThat(after).hasSize(2);
        assertThat(after).allSatisfy(t ->
                assertThat(t.getId())
                        .as("l'année %d doit conserver sa ligne, pas être recréée", t.getAnnee())
                        .isEqualTo(idsBefore.get(t.getAnnee())));

        TccAnnuel y2024 = after.stream().filter(t -> t.getAnnee() == 2024).findFirst().orElseThrow();
        assertThat(y2024.getDailyRate()).isEqualByComparingTo("480.00");
        assertThat(y2024.getTccRate()).isEqualByComparingTo("0.2400");
    }

    @Test
    @DisplayName("Une année absente de l'envoi est retirée, les autres sont conservées")
    void omittedYearIsRemovedAndKeptYearsSurvive() throws Exception {
        putTcc("""
                [{"annee":2023,"dailyRate":454.10,"tccRate":0.2290},
                 {"annee":2024,"dailyRate":478.00,"tccRate":0.2390}]""");

        Long id2024 = activeRows().stream()
                .filter(t -> t.getAnnee() == 2024).findFirst().orElseThrow().getId();

        putTcc("""
                [{"annee":2024,"dailyRate":478.00,"tccRate":0.2390},
                 {"annee":2025,"dailyRate":501.90,"tccRate":0.2490}]""");

        List<TccAnnuel> after = activeRows();
        assertThat(after).extracting(TccAnnuel::getAnnee).containsExactlyInAnyOrder(2024, 2025);
        assertThat(after.stream().filter(t -> t.getAnnee() == 2024).findFirst().orElseThrow().getId())
                .isEqualTo(id2024);
    }

    @Test
    @DisplayName("Rejouer le même envoi ne crée pas de doublon")
    void replayingTheSamePayloadIsIdempotent() throws Exception {
        String payload = """
                [{"annee":2024,"dailyRate":478.00,"tccRate":0.2390},
                 {"annee":2025,"dailyRate":501.90,"tccRate":0.2490}]""";

        putTcc(payload);
        Map<Integer, Long> idsBefore = activeRows().stream()
                .collect(java.util.stream.Collectors.toMap(TccAnnuel::getAnnee, TccAnnuel::getId));

        putTcc(payload);
        putTcc(payload);

        List<TccAnnuel> after = activeRows();
        assertThat(after).hasSize(2);
        assertThat(after).allSatisfy(t -> assertThat(t.getId()).isEqualTo(idsBefore.get(t.getAnnee())));
    }

    /** Refusé avant tout accès base ; 409 (pas 400) car le handler global mappe
     * IllegalArgumentException sur Conflict pour tout le projet. */
    @Test
    @DisplayName("Deux fois la même année dans un seul envoi → refus")
    void duplicateYearInOnePayloadIsRejected() throws Exception {
        mockMvc.perform(put("/api/resources/" + resourceId + "/tcc")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"annee":2024,"dailyRate":478.00,"tccRate":0.2390},
                                 {"annee":2024,"dailyRate":480.00,"tccRate":0.2400}]"""))
                .andExpect(status().isConflict());
    }
}

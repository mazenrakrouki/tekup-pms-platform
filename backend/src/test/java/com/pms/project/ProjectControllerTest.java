package com.pms.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.TestFixtures;
import com.pms.auth.dto.LoginRequest;
import com.pms.project.entity.ProjectStatus;
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
class ProjectControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        TestFixtures.createAdminUser(permissionRepository, roleRepository, userRepository, passwordEncoder);

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new LoginRequest("admin-test@pms.local", "Admin1234!"))))
                .andReturn();
        token = TestFixtures.extractToken(login.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("GET /api/projects sans token → 401")
    void listProjects_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/projects avec token valide → 200")
    void listProjects_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("RBAC dynamique : viewer (VIEW_PROJECT seul) peut lire mais pas créer → 200 puis 403")
    void createProject_withoutPermission_returns403() throws Exception {
        TestFixtures.createViewerUser(permissionRepository, roleRepository, userRepository, passwordEncoder);
        MvcResult viewerLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("viewer-test@pms.local", "Viewer1234!"))))
                .andReturn();
        String viewerToken = TestFixtures.extractToken(viewerLogin.getResponse().getContentAsString());

        // Le viewer a VIEW_PROJECT → la lecture est autorisée
        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        // Le viewer n'a pas CREATE_PROJECT → la création est refusée (@PreAuthorize)
        var body = Map.of("code", "DENY-01", "name", "Refusé", "status", "DRAFT");
        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/projects avec CREATE_PROJECT → 201 + Location")
    void createProject_withPermission_returns201() throws Exception {
        var body = Map.of("code", "NEW-01", "name", "Nouveau Projet",
                "status", "DRAFT");

        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.code").value("NEW-01"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("POST /api/projects avec code dupliqué → 409")
    void createProject_duplicateCode_returns409() throws Exception {
        TestFixtures.createProject(projectRepository);

        var body = Map.of("code", "TEST-01", "name", "Doublon", "status", "DRAFT");

        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/projects/{id} projet existant → 200")
    void getProject_existing_returns200() throws Exception {
        var project = TestFixtures.createProject(projectRepository);

        mockMvc.perform(get("/api/projects/" + project.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TEST-01"));
    }

    @Test
    @DisplayName("GET /api/projects/{id} inexistant → 404")
    void getProject_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/api/projects/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/projects/{id} avec EDIT_PROJECT → 200 + mis à jour")
    void updateProject_validRequest_returns200() throws Exception {
        var project = TestFixtures.createProject(projectRepository);
        var body = Map.of(
                "code", "TEST-01",
                "name", "Nom Modifié",
                "status", ProjectStatus.ACTIVE.name()
        );

        mockMvc.perform(put("/api/projects/" + project.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nom Modifié"));
    }

    @Test
    @DisplayName("DELETE /api/projects/{id} avec DELETE_PROJECT → 204 + soft-deleted")
    void deleteProject_existing_returns204() throws Exception {
        var project = TestFixtures.createProject(projectRepository);

        mockMvc.perform(delete("/api/projects/" + project.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Vérifie que le projet est soft-deleted (introuvable)
        mockMvc.perform(get("/api/projects/" + project.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}

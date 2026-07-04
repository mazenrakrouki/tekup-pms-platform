package com.pms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.auth.dto.LoginRequest;
import com.pms.auth.dto.RefreshRequest;
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

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private User testAdmin;

    @BeforeEach
    void setUp() {
        Permission p1 = permissionRepository.save(Permission.builder()
                .code("MANAGE_USERS").module("ADMIN").build());
        Permission p2 = permissionRepository.save(Permission.builder()
                .code("VIEW_PROJECT").module("PROJET").build());

        Role adminRole = roleRepository.save(Role.builder()
                .name("ADMIN")
                .permissions(Set.of(p1, p2))
                .build());

        testAdmin = userRepository.save(User.builder()
                .firstName("Test")
                .lastName("Admin")
                .email("test@pms.local")
                .passwordHash(passwordEncoder.encode("Test1234!"))
                .active(true)
                .firstLogin(false)
                .role(adminRole)
                .build());
    }

    @Test
    @DisplayName("Login avec identifiants valides retourne 200 + tokens JWT")
    void login_validCredentials_returns200WithTokens() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "Test1234!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.firstLogin").value(false));
    }

    @Test
    @DisplayName("Login avec mauvais mot de passe retourne 401")
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Login avec email inexistant retourne 401")
    void login_unknownEmail_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("nobody@pms.local", "Test1234!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Refresh avec token valide retourne nouveau accessToken")
    void refresh_validToken_returnsNewAccessToken() throws Exception {
        // 1. Login pour obtenir les tokens
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "Test1234!"))))
                .andExpect(status().isOk())
                .andReturn();

        var body = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String refreshToken = body.get("refreshToken").asText();

        // 2. Rafraîchir le token
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("Logout révoque le token : refresh suivant retourne 401")
    void logout_thenRefresh_returns401() throws Exception {
        // 1. Login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "Test1234!"))))
                .andReturn();

        var body = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken  = body.get("accessToken").asText();
        String refreshToken = body.get("refreshToken").asText();

        // 2. Logout
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // 3. Refresh avec l'ancien token → doit être rejeté (tokenVersion incrémenté)
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/me/context avec token valide retourne le profil + permissions")
    void meContext_validToken_returnsUserContext() throws Exception {
        // 1. Login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "Test1234!"))))
                .andReturn();

        var body = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = body.get("accessToken").asText();

        // 2. Appel du contexte
        mockMvc.perform(get("/api/me/context")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@pms.local"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.fullName").value("Test Admin"));
    }

    @Test
    @DisplayName("GET /api/me/context sans token retourne 401")
    void meContext_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/me/context"))
                .andExpect(status().isUnauthorized());
    }
}

package com.pms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.auth.dto.LoginRequest;
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
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static com.pms.auth.controller.AuthController.REFRESH_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("Login avec identifiants valides retourne 200 + accessToken dans le corps + cookie HttpOnly")
    void login_validCredentials_returns200WithTokens() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "Test1234!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.firstLogin").value(false))
                .andReturn();

        // Refresh token doit être dans le cookie HttpOnly, pas dans le corps
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(REFRESH_COOKIE + "=");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
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
    @DisplayName("Refresh avec cookie valide retourne nouveau accessToken et pivote le cookie")
    void refresh_validCookie_returnsNewAccessToken() throws Exception {
        // 1. Login — obtain cookie
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "Test1234!"))))
                .andExpect(status().isOk())
                .andReturn();

        String refreshCookieValue = extractRefreshCookieValue(loginResult);

        // 2. Refresh using cookie
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new MockCookie(REFRESH_COOKIE, refreshCookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("Refresh sans cookie retourne 401")
    void refresh_noCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Logout révoque le token : refresh suivant retourne 401 (rotation)")
    void logout_thenRefresh_returns401() throws Exception {
        // 1. Login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "Test1234!"))))
                .andReturn();

        var body = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken  = body.get("accessToken").asText();
        String refreshCookieValue = extractRefreshCookieValue(loginResult);

        // 2. Logout
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // 3. Refresh avec l'ancien cookie → doit être rejeté (tokenVersion incrémenté)
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new MockCookie(REFRESH_COOKIE, refreshCookieValue)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Rotation : le refresh token original est invalidé après un premier refresh")
    void refresh_rotatesToken_oldCookieRejected() throws Exception {
        // 1. Login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "Test1234!"))))
                .andReturn();

        String originalCookie = extractRefreshCookieValue(loginResult);

        // 2. Premier refresh — consomme le cookie original
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new MockCookie(REFRESH_COOKIE, originalCookie)))
                .andExpect(status().isOk());

        // 3. Deuxième refresh avec le MÊME cookie original → doit échouer (rotation)
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new MockCookie(REFRESH_COOKIE, originalCookie)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/me/context avec token valide retourne le profil + permissions")
    void meContext_validToken_returnsUserContext() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@pms.local", "Test1234!"))))
                .andReturn();

        var body = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = body.get("accessToken").asText();

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

    /** Extracts the pms_refresh cookie value from a login or refresh response's Set-Cookie header. */
    private String extractRefreshCookieValue(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).isNotNull().contains(REFRESH_COOKIE + "=");
        // Header format: "pms_refresh=<value>; Path=...; Max-Age=...; HttpOnly; SameSite=Strict"
        String afterName = header.substring(header.indexOf(REFRESH_COOKIE + "=") + REFRESH_COOKIE.length() + 1);
        int end = afterName.indexOf(';');
        return end == -1 ? afterName : afterName.substring(0, end);
    }
}

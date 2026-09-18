package com.pms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.auth.dto.LoginRequest;
import com.pms.user.entity.Role;
import com.pms.user.entity.User;
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

import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * « Se souvenir de moi » — la case etait auparavant purement decorative : aucune
 * liaison cote client, aucun champ cote serveur. Ces tests fixent le comportement
 * attendu pour qu'il ne puisse pas redevenir silencieusement inoperant.
 *
 * <p>Le troisieme test est le plus important. La rotation du jeton de rafraichissement
 * emet un nouveau jeton a chaque appel ; si la portee de la session n'etait pas portee
 * par le jeton lui-meme, l'utilisateur retomberait a la duree courte des le premier
 * rafraichissement, sans rien remarquer avant d'etre deconnecte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RememberMeTest {

    /** Valeurs attendues du profil `test`, en secondes. */
    private static final long SHORT_SESSION = 86_400L;     // refresh-token-expiration
    private static final long REMEMBER_ME   = 2_592_000L;  // 30 jours

    private static final Pattern MAX_AGE = Pattern.compile("Max-Age=(\\d+)");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Role role = roleRepository.save(Role.builder()
                .name("REMEMBER_TEST")
                .permissions(new HashSet<>())
                .build());
        userRepository.save(User.builder()
                .firstName("Remember").lastName("Test")
                .email("remember@pms.local")
                .passwordHash(passwordEncoder.encode("Remember1234!"))
                .active(true).firstLogin(false).role(role)
                .build());
    }

    private MvcResult login(boolean rememberMe) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("remember@pms.local", "Remember1234!", rememberMe))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static long maxAgeOf(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).as("Set-Cookie header").isNotNull();
        Matcher m = MAX_AGE.matcher(header);
        assertThat(m.find()).as("Max-Age present in %s", header).isTrue();
        return Long.parseLong(m.group(1));
    }

    private static String cookieValueOf(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    @Test
    @DisplayName("Sans la case cochee, la session garde la duree courte")
    void login_withoutRememberMe_usesShortSession() throws Exception {
        assertThat(maxAgeOf(login(false))).isEqualTo(SHORT_SESSION);
    }

    @Test
    @DisplayName("Case cochee : le cookie dure 30 jours")
    void login_withRememberMe_usesThirtyDays() throws Exception {
        assertThat(maxAgeOf(login(true))).isEqualTo(REMEMBER_ME);
    }

    @Test
    @DisplayName("La rotation conserve la session longue")
    void refresh_preservesRememberMe() throws Exception {
        String refreshToken = cookieValueOf(login(true));

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("pms_refresh", refreshToken)))
                .andExpect(status().isOk())
                .andReturn();

        // Sans la revendication portee par le jeton, on retomberait ici a SHORT_SESSION.
        assertThat(maxAgeOf(refreshed)).isEqualTo(REMEMBER_ME);
    }

    @Test
    @DisplayName("La rotation d'une session courte reste courte")
    void refresh_withoutRememberMe_staysShort() throws Exception {
        String refreshToken = cookieValueOf(login(false));

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("pms_refresh", refreshToken)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(maxAgeOf(refreshed)).isEqualTo(SHORT_SESSION);
    }
}

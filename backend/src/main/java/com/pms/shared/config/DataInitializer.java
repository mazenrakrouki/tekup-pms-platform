package com.pms.shared.config;

import com.pms.user.entity.User;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crée les comptes de démonstration au démarrage.
 *
 * <p>Ces comptes utilisent des mots de passe connus et publiés dans la documentation :
 * ils rendent la plateforme immédiatement démontrable après un {@code docker compose up}.
 * Un déploiement réel doit les désactiver avec {@code pms.demo.seed-users=false}.
 *
 * <p>Le comportement par défaut ({@code matchIfMissing = true}) préserve celui existant.
 */
@Component
@ConditionalOnProperty(name = "pms.demo.seed-users", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (roleRepository.findByName("ADMIN").isEmpty()) {
            log.warn("Rôles RBAC introuvables — DataInitializer ignoré (normal en environnement de test)");
            return;
        }

        // Comptes de démonstration — un par rôle, mot de passe connu, sans first-login forcé
        upsertUser("admin@pms.local",     "Admin",     "PMS",         "Admin1234!",     "ADMIN");
        upsertUser("directeur@pms.local", "Sami",      "Directeur",   "Directeur1234!", "DIRECTEUR");
        upsertUser("chef@pms.local",      "Leila",     "Chef",        "Chef1234!",      "CHEF_PROJET");
        upsertUser("dev@pms.local",       "Omar",      "Developpeur", "Dev1234!",       "DEVELOPPEUR");

        // Comptes administrateurs supplémentaires (demandés)
        upsertUser("admin2@pms.local",    "Admin",     "Deux",        "Admin2@2026!",   "ADMIN");
        upsertUser("admin3@pms.local",    "Admin",     "Trois",       "Admin3@2026!",   "ADMIN");

        // Comptes démo momo (V26 les crée avec hash pgcrypto ; on synchronise ici le vrai mot de passe)
        upsertUser("momo-directeur@pms.local", "Mohamed", "Ben Directeur", "Momo123456", "DIRECTEUR");
        upsertUser("momo-chef@pms.local",      "Mohamed", "Ben Chef",      "Momo123456", "CHEF_PROJET");
        upsertUser("momo-dev@pms.local",       "Mohamed", "Ben Dev",       "Momo123456", "DEVELOPPEUR");
    }

    /** Crée l'utilisateur s'il n'existe pas, sinon réinitialise son mot de passe + rôle (idempotent, mode dev). */
    private void upsertUser(String email, String firstName, String lastName, String password, String roleName) {
        var role = roleRepository.findByName(roleName).orElse(null);
        if (role == null) {
            log.warn("Rôle {} introuvable — utilisateur {} ignoré", roleName, email);
            return;
        }

        var existing = userRepository.findActiveByEmailWithRole(email);
        if (existing.isPresent()) {
            User u = existing.get();
            u.setPasswordHash(passwordEncoder.encode(password));
            u.setFirstLogin(false);
            u.setRole(role);
            userRepository.save(u);
            log.info("Utilisateur réinitialisé : {} / {} ({})", email, password, roleName);
        } else {
            User u = User.builder()
                    .firstName(firstName)
                    .lastName(lastName)
                    .email(email)
                    .passwordHash(passwordEncoder.encode(password))
                    .active(true)
                    .firstLogin(false)
                    .role(role)
                    .build();
            userRepository.save(u);
            log.info("Utilisateur créé : {} / {} ({})", email, password, roleName);
        }
    }
}

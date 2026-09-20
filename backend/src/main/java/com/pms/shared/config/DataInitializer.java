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

// Creates/resets the demonstration accounts (one per role) at start-up, with passwords
// published in docs/demo-accounts.md. Safe to re-run: repairs a demo account instead of
// failing on a duplicate e-mail. Passwords are intentionally public for the demo; the whole
// class is switched off via pms.demo.seed-users=false (set PMS_DEMO_SEED_USERS=false before
// exposing the app, per docs/DEPLOYMENT.md and ADR-026).

/**
 * Creates the demonstration accounts at start-up. A real deployment must disable this with
 * {@code pms.demo.seed-users=false}; the default ({@code matchIfMissing = true}) keeps it on.
 */
@Component
// Bean is created only when pms.demo.seed-users=true or absent (matchIfMissing). Not building
// the bean at all is stronger than an "if" inside run() that someone could delete. Set via
// PMS_DEMO_SEED_USERS in docker-compose.yml / the .env file.
@ConditionalOnProperty(name = "pms.demo.seed-users", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    // ApplicationRunner (not a Flyway migration) because the bcrypt hash must be produced by
    // the application's own encoder, whose cost factor a migration couldn't track.

    private final UserRepository userRepository;
    // Reads roles created by Flyway V2; this class never creates a role itself.
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    // Whole run is one transaction: a failure partway through must not leave a half-seeded
    // database that the next start can't tell apart from a complete one.
    @Transactional
    public void run(ApplicationArguments args) {
        // Test profile runs Flyway-disabled H2, so no role rows exist there; skip rather than
        // crash every integration test on nine missing roles.
        if (roleRepository.findByName("ADMIN").isEmpty()) {
            log.warn("Rôles RBAC introuvables — DataInitializer ignoré (normal en environnement de test)");
            return;
        }

        // One account per role, firstLogin off so FirstLoginFilter (H-2) doesn't force a
        // password change that would invalidate the documented demo password.
        upsertUser("admin@pms.local",     "Admin",     "PMS",         "Admin1234!",     "ADMIN");
        upsertUser("directeur@pms.local", "Sami",      "Directeur",   "Directeur1234!", "DIRECTEUR");
        upsertUser("chef@pms.local",      "Leila",     "Chef",        "Chef1234!",      "CHEF_PROJET");
        upsertUser("dev@pms.local",       "Omar",      "Developpeur", "Dev1234!",       "DEVELOPPEUR");

        // Extra administrator accounts (asked for).
        upsertUser("admin2@pms.local",    "Admin",     "Deux",        "Admin2@2026!",   "ADMIN");
        upsertUser("admin3@pms.local",    "Admin",     "Trois",       "Admin3@2026!",   "ADMIN");

        // Flyway V26 already creates these three rows with a PostgreSQL-computed hash;
        // re-encoding here with the app's own encoder guarantees the documented password
        // works regardless of whether the two bcrypt implementations agree.
        upsertUser("momo-directeur@pms.local", "Mohamed", "Ben Directeur", "Momo123456", "DIRECTEUR");
        upsertUser("momo-chef@pms.local",      "Mohamed", "Ben Chef",      "Momo123456", "CHEF_PROJET");
        upsertUser("momo-dev@pms.local",       "Mohamed", "Ben Dev",       "Momo123456", "DEVELOPPEUR");
    }

    /** Creates the user if absent, otherwise resets password/role — idempotent so re-running at every start is safe. */
    private void upsertUser(String email, String firstName, String lastName, String password, String roleName) {
        var role = roleRepository.findByName(roleName).orElse(null);
        if (role == null) {
            // Typo or a migration that didn't run - skip this one account rather than fail boot.
            log.warn("Rôle {} introuvable — utilisateur {} ignoré", roleName, email);
            return;
        }

        var existing = userRepository.findActiveByEmailWithRole(email);
        if (existing.isPresent()) {
            User u = existing.get();
            // New hash every run (bcrypt salts randomly) - the column changing is expected, not a bug.
            u.setPasswordHash(passwordEncoder.encode(password));
            u.setFirstLogin(false);
            u.setRole(role);
            userRepository.save(u);
            log.info("Utilisateur réinitialisé : {} / {} ({})", email, password, roleName);
        } else {
            // Builder, not a 7-arg constructor: keeps field names visible at the call site.
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

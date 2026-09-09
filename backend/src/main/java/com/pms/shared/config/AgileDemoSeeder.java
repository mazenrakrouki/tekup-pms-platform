package com.pms.shared.config;

import com.pms.agile.entity.BacklogItem;
import com.pms.agile.entity.BacklogItemStatus;
import com.pms.agile.entity.BacklogPriority;
import com.pms.agile.entity.Sprint;
import com.pms.agile.entity.SprintStatus;
import com.pms.agile.repository.BacklogItemRepository;
import com.pms.agile.repository.SprintRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Seeds a realistic sprint board for one demonstration project.
 *
 * <p>The agile module (V27) shipped with an empty backlog, so every screen that
 * shows it — including the ones reproduced in the report — displayed an empty
 * state. This seeder gives {@code ENT-ELEC-2026} a board that is worth looking
 * at: four sprints spanning the project, items in all three columns of the
 * active sprint, and a product backlog of items not yet committed.
 *
 * <p>It runs after {@link EnterpriseDataSeeder} (which creates the project) and
 * is guarded by its own check, so it seeds exactly once and never on a project
 * that already has sprints. It is demonstration data only: no financial value
 * is written, and nothing outside the two agile tables is touched.
 */
@Component
@Order(210)
@RequiredArgsConstructor
@Slf4j
public class AgileDemoSeeder implements ApplicationRunner {

    private final ProjectRepository     projectRepo;
    private final SprintRepository      sprintRepo;
    private final BacklogItemRepository backlogRepo;

    /** The project whose window contains the present, so one sprint reads as active. */
    private static final String TARGET_CODE = "ENT-ELEC-2026";

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Project project = projectRepo.findByCodeAndDeletedFalse(TARGET_CODE).orElse(null);
        if (project == null) {
            log.info("AgileDemoSeeder — projet {} absent, seeder ignoré.", TARGET_CODE);
            return;
        }
        if (!sprintRepo.findActiveByProjectId(project.getId()).isEmpty()) {
            log.info("AgileDemoSeeder — sprints déjà présents sur {}, ignoré.", TARGET_CODE);
            return;
        }

        log.info("AgileDemoSeeder — génération du board agile de démonstration sur {}...", TARGET_CODE);

        Sprint s1 = sprint(project, "Sprint 1 — Cadrage et socle technique",
                "Mettre en place l'ossature technique du système et l'authentification des agents.",
                LocalDate.of(2026, 2, 2), LocalDate.of(2026, 3, 13), SprintStatus.CLOSED);
        Sprint s2 = sprint(project, "Sprint 2 — Référentiel électeurs",
                "Constituer le référentiel national des électeurs et ses contrôles de cohérence.",
                LocalDate.of(2026, 3, 16), LocalDate.of(2026, 4, 24), SprintStatus.CLOSED);
        Sprint s3 = sprint(project, "Sprint 3 — Bureaux de vote et agents",
                "Découper le territoire en bureaux de vote et y affecter les agents électoraux.",
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 9, 25), SprintStatus.ACTIVE);
        Sprint s4 = sprint(project, "Sprint 4 — Opérations de scrutin",
                "Saisir les procès-verbaux et consolider les résultats par circonscription.",
                LocalDate.of(2026, 9, 28), LocalDate.of(2026, 11, 6), SprintStatus.PLANNED);

        sprintRepo.saveAll(List.of(s1, s2, s3, s4));

        backlogRepo.saveAll(List.of(
                // Sprint 1 — delivered
                item(project, s1, "Authentification des agents électoraux",
                        BacklogPriority.HIGH, BacklogItemStatus.DONE, "5.00"),
                item(project, s1, "Socle technique et chaîne d'intégration continue",
                        BacklogPriority.HIGH, BacklogItemStatus.DONE, "8.00"),
                item(project, s1, "Journalisation des accès et piste d'audit",
                        BacklogPriority.MEDIUM, BacklogItemStatus.DONE, "3.00"),

                // Sprint 2 — delivered
                item(project, s2, "Import du référentiel national des électeurs",
                        BacklogPriority.CRITICAL, BacklogItemStatus.DONE, "13.00"),
                item(project, s2, "Contrôles de cohérence et détection des doublons",
                        BacklogPriority.HIGH, BacklogItemStatus.DONE, "8.00"),
                item(project, s2, "Recherche d'un électeur par identifiant national",
                        BacklogPriority.MEDIUM, BacklogItemStatus.DONE, "5.00"),

                // Sprint 3 — in flight, spread across the three columns
                item(project, s3, "Découpage en circonscriptions et bureaux de vote",
                        BacklogPriority.CRITICAL, BacklogItemStatus.IN_PROGRESS, "13.00"),
                item(project, s3, "Affectation des agents aux bureaux de vote",
                        BacklogPriority.HIGH, BacklogItemStatus.IN_PROGRESS, "8.00"),
                item(project, s3, "Fiche bureau de vote — génération et impression",
                        BacklogPriority.LOW, BacklogItemStatus.DONE, "2.00"),
                item(project, s3, "Tableau de bord de préparation du scrutin",
                        BacklogPriority.MEDIUM, BacklogItemStatus.TODO, "5.00"),
                item(project, s3, "Export des listes d'émargement",
                        BacklogPriority.MEDIUM, BacklogItemStatus.TODO, "3.00"),
                item(project, s3, "Notification des agents par SMS",
                        BacklogPriority.LOW, BacklogItemStatus.TODO, "2.00"),

                // Sprint 4 — planned
                item(project, s4, "Saisie des procès-verbaux de dépouillement",
                        BacklogPriority.CRITICAL, BacklogItemStatus.TODO, "13.00"),
                item(project, s4, "Consolidation des résultats par circonscription",
                        BacklogPriority.HIGH, BacklogItemStatus.TODO, "8.00"),
                item(project, s4, "Publication des résultats provisoires",
                        BacklogPriority.HIGH, BacklogItemStatus.TODO, "5.00"),

                // Product backlog — not committed to any sprint
                item(project, null, "Application mobile de consultation pour l'électeur",
                        BacklogPriority.MEDIUM, BacklogItemStatus.TODO, "21.00"),
                item(project, null, "Tableau de bord pour les observateurs internationaux",
                        BacklogPriority.LOW, BacklogItemStatus.TODO, "13.00"),
                item(project, null, "Archivage légal des scrutins",
                        BacklogPriority.MEDIUM, BacklogItemStatus.TODO, "8.00"),
                item(project, null, "Mise en accessibilité des écrans publics",
                        BacklogPriority.MEDIUM, BacklogItemStatus.TODO, "5.00")
        ));

        log.info("AgileDemoSeeder — 4 sprints et 19 éléments de backlog créés sur {}.", TARGET_CODE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    private Sprint sprint(Project project, String name, String goal,
                          LocalDate start, LocalDate end, SprintStatus status) {
        return Sprint.builder()
                .project(project).name(name).goal(goal)
                .startDate(start).endDate(end).status(status)
                .build();
    }

    private BacklogItem item(Project project, Sprint sprint, String title,
                             BacklogPriority priority, BacklogItemStatus status, String days) {
        return BacklogItem.builder()
                .project(project).sprint(sprint).title(title)
                .priority(priority).status(status)
                .estimateDays(new BigDecimal(days))
                .build();
    }
}

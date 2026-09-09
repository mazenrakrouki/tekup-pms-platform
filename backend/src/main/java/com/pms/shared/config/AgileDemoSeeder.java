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
import com.pms.team.entity.TeamAssignment;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Gives every demonstration project a populated sprint board.
 *
 * <p>The agile module shipped with empty tables, so every board in the platform
 * showed an empty state. This seeder fills them: each project gets a series of
 * three-week sprints laid out inside its own contractual window, items spread
 * across the three board columns, and a product backlog of work not yet
 * committed to any sprint.
 *
 * <p>Two properties matter. A re-run replaces its own data and never touches a
 * board somebody built by hand: JPA auditing records {@code system} on rows
 * written outside a request and the caller's username on rows written through
 * the API, which is exactly the distinction needed. And it is
 * <b>deterministic</b>: the generator is seeded from the project id, so the same
 * project always receives the same board rather than a different one on every
 * fresh database.
 *
 * <p>Sprints run back to back, three weeks each, anchored on the calendar rather
 * than spread thinly across the contract: a project running today gets a series
 * positioned so that one sprint contains today, a finished project gets one
 * ending with its own window, and a project not yet started gets one beginning
 * with it. Sprint status then follows from the dates instead of being invented,
 * and item status follows from the sprint — everything done in a closed sprint,
 * nothing started in a planned one, and real work in flight in the active one.
 *
 * <p>This is demonstration data only. Nothing outside the two agile tables is
 * written, and no financial value is produced.
 */
@Component
@Order(210)
@RequiredArgsConstructor
@Slf4j
public class AgileDemoSeeder implements ApplicationRunner {

    private final ProjectRepository        projectRepo;
    private final SprintRepository         sprintRepo;
    private final BacklogItemRepository    backlogRepo;
    private final TeamAssignmentRepository teamRepo;

    private static final int SPRINT_WEEKS = 3;
    private static final int MIN_SPRINTS  = 3;
    private static final int MAX_SPRINTS  = 5;

    /** Legacy marker, still recognised when deciding what this seeder may replace. */
    private static final String MARKER = "seed:agile";

    /** Fallback span for a project that never recorded an end date. */
    private static final int DEFAULT_MONTHS = 12;

    /**
     * The project the report reproduces in its figures. Its board is written out
     * in full rather than generated, so the screenshots in the report stay
     * reproducible from a fresh database.
     */
    private static final String SHOWCASE_CODE = "ENT-ELEC-2026";

    private static final String[][] SHOWCASE_SPRINTS = {
        {"Sprint 1 — Cadrage et socle technique",
         "Mettre en place l'ossature technique du système et l'authentification des agents.",
         "2026-02-02", "2026-03-13", "CLOSED"},
        {"Sprint 2 — Référentiel électeurs",
         "Constituer le référentiel national des électeurs et ses contrôles de cohérence.",
         "2026-03-16", "2026-04-24", "CLOSED"},
        {"Sprint 3 — Bureaux de vote et agents",
         "Découper le territoire en bureaux de vote et y affecter les agents électoraux.",
         "2026-08-17", "2026-09-25", "ACTIVE"},
        {"Sprint 4 — Opérations de scrutin",
         "Saisir les procès-verbaux et consolider les résultats par circonscription.",
         "2026-09-28", "2026-11-06", "PLANNED"},
    };

    /** {sprint index or -1 for product backlog, title, priority, status, days} */
    private static final String[][] SHOWCASE_ITEMS = {
        {"0", "Authentification des agents électoraux",            "HIGH",     "DONE",        "5.00"},
        {"0", "Socle technique et chaîne d'intégration continue",  "HIGH",     "DONE",        "8.00"},
        {"0", "Journalisation des accès et piste d'audit",         "MEDIUM",   "DONE",        "3.00"},
        {"1", "Import du référentiel national des électeurs",      "CRITICAL", "DONE",       "13.00"},
        {"1", "Contrôles de cohérence et détection des doublons",  "HIGH",     "DONE",        "8.00"},
        {"1", "Recherche d'un électeur par identifiant national",  "MEDIUM",   "DONE",        "5.00"},
        {"2", "Découpage en circonscriptions et bureaux de vote",  "CRITICAL", "IN_PROGRESS","13.00"},
        {"2", "Affectation des agents aux bureaux de vote",        "HIGH",     "IN_PROGRESS", "8.00"},
        {"2", "Fiche bureau de vote — génération et impression",   "LOW",      "DONE",        "2.00"},
        {"2", "Tableau de bord de préparation du scrutin",         "MEDIUM",   "TODO",        "5.00"},
        {"2", "Export des listes d'émargement",                    "MEDIUM",   "TODO",        "3.00"},
        {"2", "Notification des agents par SMS",                   "LOW",      "TODO",        "2.00"},
        {"3", "Saisie des procès-verbaux de dépouillement",        "CRITICAL", "TODO",       "13.00"},
        {"3", "Consolidation des résultats par circonscription",   "HIGH",     "TODO",        "8.00"},
        {"3", "Publication des résultats provisoires",             "HIGH",     "TODO",        "5.00"},
        {"-1", "Application mobile de consultation pour l'électeur",   "MEDIUM", "TODO", "21.00"},
        {"-1", "Tableau de bord pour les observateurs internationaux", "LOW",    "TODO", "13.00"},
        {"-1", "Archivage légal des scrutins",                         "MEDIUM", "TODO",  "8.00"},
        {"-1", "Mise en accessibilité des écrans publics",             "MEDIUM", "TODO",  "5.00"},
    };

    /** Phase names, applicable to any of the IT projects in the demonstration set. */
    private static final String[][] PHASES = {
        {"Cadrage et socle technique",
         "Mettre en place l'ossature technique du projet et valider les choix d'architecture."},
        {"Conception détaillée",
         "Détailler les spécifications fonctionnelles et techniques des modules à livrer."},
        {"Développement du cœur métier",
         "Réaliser les fonctionnalités centrales attendues par le client."},
        {"Intégration et recette",
         "Intégrer les modules, corriger les anomalies et préparer la recette utilisateur."},
        {"Déploiement et transfert de compétences",
         "Déployer la solution et former les équipes du client à son exploitation."},
    };

    /** Work items drawn on per project; the pool is deliberately generic. */
    private static final String[] ITEMS = {
        "Mise en place de l'environnement de développement",
        "Modèle de données et scripts de migration",
        "Authentification et gestion des habilitations",
        "Import des données de référence",
        "Contrôles de cohérence sur les données importées",
        "Écrans de consultation des dossiers",
        "Écrans de saisie et de mise à jour",
        "Moteur de recherche multicritère",
        "Génération des états et exports",
        "Tableau de bord de pilotage",
        "Notifications par courriel",
        "Journalisation et piste d'audit",
        "Reprise de l'historique existant",
        "Interface avec le système du client",
        "Gestion documentaire des pièces jointes",
        "Paramétrage des règles de gestion",
        "Optimisation des temps de réponse",
        "Tests de charge et de montée en charge",
        "Rédaction du manuel d'utilisation",
        "Rédaction du manuel d'installation",
        "Recette fonctionnelle avec le client",
        "Correction des anomalies de recette",
        "Sécurisation et revue de vulnérabilités",
        "Sauvegarde et plan de reprise",
        "Mise en production et bascule",
        "Formation des utilisateurs finaux",
        "Accessibilité des écrans publics",
        "Version mobile de consultation",
        "Tableau de bord décisionnel complémentaire",
        "Archivage légal des données",
    };

    private static final BacklogPriority[] PRIORITIES = {
        BacklogPriority.CRITICAL, BacklogPriority.HIGH,
        BacklogPriority.HIGH, BacklogPriority.MEDIUM,
        BacklogPriority.MEDIUM, BacklogPriority.LOW,
    };

    private static final String[] ESTIMATES = {"2.00", "3.00", "5.00", "8.00", "13.00"};

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Project> projects = projectRepo.findAllActive();
        if (projects.isEmpty()) {
            log.info("AgileDemoSeeder — aucun projet actif, seeder ignoré.");
            return;
        }

        int seeded = 0, sprints = 0, items = 0;
        for (Project project : projects) {
            if (hasHandMadeBoard(project)) {
                continue;                       // somebody built this one: leave it alone
            }
            clearOwnRows(project);              // replace our own previous output
            int[] counts = seed(project);
            if (counts[0] > 0) {
                seeded++;
                sprints += counts[0];
                items += counts[1];
            }
        }

        if (seeded == 0) {
            log.info("AgileDemoSeeder — tous les projets ont déjà un board, ignoré.");
        } else {
            log.info("AgileDemoSeeder — {} projets alimentés : {} sprints, {} éléments de backlog.",
                     seeded, sprints, items);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    /**
     * JPA auditing stamps {@code createdBy} on persist, so a value set here would
     * be overwritten: outside a request {@link SpringSecurityAuditorAware} writes
     * {@code system}, and inside one it writes the caller's username. That is the
     * distinction this seeder needs — {@code system} (or a null from an older row)
     * means nobody authored the row, so it is ours to replace, while any username
     * means a person built that board and it must be left alone.
     */
    private boolean ours(String createdBy) {
        return createdBy == null || "system".equals(createdBy) || MARKER.equals(createdBy);
    }

    /** True when the project carries sprints this seeder did not create. */
    private boolean hasHandMadeBoard(Project project) {
        return sprintRepo.findActiveByProjectId(project.getId()).stream()
                .anyMatch(s -> !ours(s.getCreatedBy()));
    }

    /** Removes this seeder's previous output, items first so the sprint keys are free. */
    private void clearOwnRows(Project project) {
        List<BacklogItem> items = backlogRepo.findActiveByProjectId(project.getId()).stream()
                .filter(i -> ours(i.getCreatedBy()))
                .toList();
        if (!items.isEmpty()) {
            backlogRepo.deleteAll(items);
            backlogRepo.flush();
        }
        List<Sprint> sprints = sprintRepo.findActiveByProjectId(project.getId()).stream()
                .filter(s -> ours(s.getCreatedBy()))
                .toList();
        if (!sprints.isEmpty()) {
            sprintRepo.deleteAll(sprints);
            sprintRepo.flush();
        }
    }

    private int[] seed(Project project) {
        if (SHOWCASE_CODE.equals(project.getCode())) {
            return seedShowcase(project);
        }
        LocalDate start = project.getStartDate();
        if (start == null) {
            return new int[]{0, 0};
        }
        LocalDate end = project.getEndDate() != null
                ? project.getEndDate()
                : start.plusMonths(DEFAULT_MONTHS);
        if (!end.isAfter(start)) {
            return new int[]{0, 0};
        }

        Random random = new Random(project.getId());
        LocalDate today = LocalDate.now();

        long weeks = ChronoUnit.WEEKS.between(start, end);
        int count = (int) Math.min(MAX_SPRINTS, Math.max(MIN_SPRINTS, weeks / SPRINT_WEEKS));
        LocalDate seriesStart = anchor(start, end, today, count);

        List<Sprint> sprints = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate from = seriesStart.plusWeeks((long) i * SPRINT_WEEKS);
            LocalDate to = from.plusWeeks(SPRINT_WEEKS).minusDays(1);

            SprintStatus status = to.isBefore(today) ? SprintStatus.CLOSED
                                : from.isAfter(today) ? SprintStatus.PLANNED
                                : SprintStatus.ACTIVE;

            String[] phase = PHASES[i % PHASES.length];
            sprints.add(Sprint.builder()
                    .project(project)
                    .name("Sprint " + (i + 1) + " — " + phase[0])
                    .goal(phase[1])
                    .startDate(from).endDate(to)
                    .status(status)
                    .build());
        }
        sprintRepo.saveAll(sprints);

        List<User> team = teamMembers(project);
        int offset = random.nextInt(ITEMS.length);
        int turn = 0;
        List<BacklogItem> items = new ArrayList<>();

        for (Sprint sprint : sprints) {
            int perSprint = 3 + random.nextInt(2);          // three or four
            for (int i = 0; i < perSprint; i++) {
                items.add(item(project, sprint, nextTitle(offset++),
                               PRIORITIES[random.nextInt(PRIORITIES.length)],
                               statusIn(sprint, i, perSprint, random),
                               ESTIMATES[random.nextInt(ESTIMATES.length)],
                               // Committed work has an owner; the last card of a
                               // planned sprint is left free, which is the normal
                               // state of work nobody has picked up yet.
                               sprint.getStatus() == SprintStatus.PLANNED && i == perSprint - 1
                                       ? null : pick(team, turn++)));
            }
        }

        int loose = 2 + random.nextInt(3);                  // two to four, uncommitted
        for (int i = 0; i < loose; i++) {
            items.add(item(project, null, nextTitle(offset++),
                           PRIORITIES[random.nextInt(PRIORITIES.length)],
                           BacklogItemStatus.TODO,
                           ESTIMATES[random.nextInt(ESTIMATES.length)],
                           null));                          // product backlog: unassigned
        }

        backlogRepo.saveAll(items);
        return new int[]{sprints.size(), items.size()};
    }

    /** Writes out the board the report reproduces, verbatim rather than generated. */
    private int[] seedShowcase(Project project) {
        List<Sprint> sprints = new ArrayList<>();
        for (String[] row : SHOWCASE_SPRINTS) {
            sprints.add(Sprint.builder()
                    .project(project)
                    .name(row[0]).goal(row[1])
                    .startDate(LocalDate.parse(row[2]))
                    .endDate(LocalDate.parse(row[3]))
                    .status(SprintStatus.valueOf(row[4]))
                    .build());
        }
        sprintRepo.saveAll(sprints);

        List<User> team = teamMembers(project);
        int turn = 0;
        List<BacklogItem> items = new ArrayList<>();

        for (String[] row : SHOWCASE_ITEMS) {
            int index = Integer.parseInt(row[0]);
            Sprint sprint = index >= 0 ? sprints.get(index) : null;
            items.add(item(project, sprint, row[1],
                           BacklogPriority.valueOf(row[2]),
                           BacklogItemStatus.valueOf(row[3]),
                           row[4],
                           sprint == null ? null : pick(team, turn++)));
        }
        backlogRepo.saveAll(items);
        return new int[]{sprints.size(), items.size()};
    }

    /**
     * Places the series so the board is worth looking at: a project running today
     * gets a sprint containing today, a finished one gets a series ending with its
     * own window, and one not yet started gets a series beginning with it.
     */
    private LocalDate anchor(LocalDate start, LocalDate end, LocalDate today, int count) {
        long span = (long) count * SPRINT_WEEKS;
        LocalDate latest = end.minusWeeks(span);
        if (latest.isBefore(start)) {
            return start;                                   // window too short to place freely
        }
        if (today.isBefore(start)) {
            return start;
        }
        if (today.isAfter(end)) {
            return latest;
        }
        // Put the sprint containing today roughly in the middle of the series.
        LocalDate candidate = today.minusWeeks((long) (count / 2) * SPRINT_WEEKS);
        if (candidate.isBefore(start)) return start;
        if (candidate.isAfter(latest)) return latest;
        return candidate;
    }

    private List<User> teamMembers(Project project) {
        return teamRepo.findActiveByProjectId(project.getId()).stream()
                .map(TeamAssignment::getUser)
                .filter(u -> u != null && !u.isDeleted())
                .toList();
    }

    /**
     * Round-robin rather than random: drawing at random from a team of five puts
     * half the sprint on one person often enough to look wrong, whereas a sprint
     * is normally shared out.
     */
    private User pick(List<User> team, int turn) {
        return team.isEmpty() ? null : team.get(Math.floorMod(turn, team.size()));
    }

    /**
     * A closed sprint has everything done, a planned one has nothing started, and
     * the active one is genuinely in flight — which is what makes the board worth
     * looking at.
     */
    private BacklogItemStatus statusIn(Sprint sprint, int index, int total, Random random) {
        switch (sprint.getStatus()) {
            case CLOSED:
                return BacklogItemStatus.DONE;
            case PLANNED:
                return BacklogItemStatus.TODO;
            default:
                if (index == 0) return BacklogItemStatus.DONE;
                if (index < Math.max(2, total - 1)) return BacklogItemStatus.IN_PROGRESS;
                return random.nextBoolean() ? BacklogItemStatus.TODO : BacklogItemStatus.IN_PROGRESS;
        }
    }

    private String nextTitle(int index) {
        return ITEMS[Math.floorMod(index, ITEMS.length)];
    }

    private BacklogItem item(Project project, Sprint sprint, String title,
                             BacklogPriority priority, BacklogItemStatus status,
                             String days, User assignee) {
        return BacklogItem.builder()
                .project(project).sprint(sprint).title(title)
                .priority(priority).status(status)
                .estimateDays(new BigDecimal(days))
                .assignee(assignee)
                .build();
    }
}

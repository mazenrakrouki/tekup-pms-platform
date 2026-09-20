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

// Start-up job (ApplicationRunner, @Order(210), after EnterpriseDataSeeder) that fills
// sprints/backlog_items with demo data so every project opens on a populated board.
// Runs with nobody logged in, so no @PreAuthorize/ProjectScopeInterceptor applies here,
// and it never touches the DI — only these two agile tables.

/**
 * Gives every demonstration project a sprint board (three-week sprints, cards spread over
 * TODO/IN_PROGRESS/DONE). Re-runs replace only rows it created itself (see {@link #ours}),
 * and generation is seeded on the project id so boards stay stable across restarts.
 * ENT-ELEC-2026 is written by hand instead (SHOWCASE_SPRINTS/ITEMS) since the report reproduces it.
 */
@Component
@Order(210)
@RequiredArgsConstructor
@Slf4j
public class AgileDemoSeeder implements ApplicationRunner {

    // projectRepo/teamRepo are read-only; sprintRepo/backlogRepo are also written to.
    private final ProjectRepository        projectRepo;
    private final SprintRepository         sprintRepo;
    private final BacklogItemRepository    backlogRepo;
    private final TeamAssignmentRepository teamRepo;

    // 3-week sprints, 3-5 per project: floor keeps short projects showing past/current/future
    // sprints, ceiling stops long projects generating dozens nobody scrolls through.
    private static final int SPRINT_WEEKS = 3;
    private static final int MIN_SPRINTS  = 3;
    private static final int MAX_SPRINTS  = 5;

    /** Marker used by an older version of this seeder; {@link #ours(String)} still accepts it. */
    private static final String MARKER = "seed:agile";

    /** Fallback project length when endDate is null, used only to size the sprint series. */
    private static final int DEFAULT_MONTHS = 12;

    /** Project whose board is written by hand instead of generated, since the report reproduces it. */
    private static final String SHOWCASE_CODE = "ENT-ELEC-2026";

    // {name, goal, start date, end date, status} per sprint of the showcase project. Dates and
    // status are fixed by hand to match the report's figures, so status does not track the
    // calendar — once the ACTIVE sprint's end date passes, it stays ACTIVE until edited here.
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

    // First column is the sprint's POSITION in SHOWCASE_SPRINTS (or -1 for backlog), not its
    // DB id — ids don't exist yet when this constant is written; seedShowcase() resolves it
    // via sprints.get(index) after saveAll(), which preserves list order.
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

    // {short name, goal}; sprint i takes PHASES[i % length] so a life-cycle order (framing to
    // deployment) is kept, and the modulo just guards against MAX_SPRINTS ever exceeding 5.
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

    // Pool of 30 titles; seed() picks a random start then walks forward (nextTitle()) so no
    // title repeats on one board. 30 covers the worst case of 5 sprints x 4 cards + 4 loose.
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

    // Weighted bag (HIGH/MEDIUM twice, CRITICAL/LOW once) instead of a uniform draw, so
    // roughly 1 in 6 cards is CRITICAL rather than 1 in 4 — closer to a real backlog.
    private static final BacklogPriority[] PRIORITIES = {
        BacklogPriority.CRITICAL, BacklogPriority.HIGH,
        BacklogPriority.HIGH, BacklogPriority.MEDIUM,
        BacklogPriority.MEDIUM, BacklogPriority.LOW,
    };

    // Fibonacci story-point scale in man-days. Kept as Strings so item()'s new BigDecimal(String)
    // stores the exact digits (NUMERIC(6,2)) instead of a double's binary rounding error.
    private static final String[] ESTIMATES = {"2.00", "3.00", "5.00", "8.00", "13.00"};

    /**
     * Entry point: walks every active project, skips hand-built boards, rebuilds the rest.
     * {@code @Transactional} covers the whole loop so a mid-run failure rolls back cleanly
     * instead of leaving a project with its old board deleted and the new one half-written.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Archived projects are deliberately excluded: finished work doesn't need a fresh board.
        List<Project> projects = projectRepo.findAllActive();
        // Guard for an empty DB (e.g. other seeders disabled), so the operator sees why.
        if (projects.isEmpty()) {
            log.info("AgileDemoSeeder — aucun projet actif, seeder ignoré.");
            return;
        }

        // Running totals for the summary line below.
        int seeded = 0, sprints = 0, items = 0;
        for (Project project : projects) {
            // Skip projects a real user already built a board on.
            if (hasHandMadeBoard(project)) {
                continue;                       // somebody built this one: leave it alone
            }
            clearOwnRows(project);              // replace our own previous output
            int[] counts = seed(project);
            // counts[0] == 0 means seed() refused the project (no/invalid dates): not counted.
            if (counts[0] > 0) {
                seeded++;
                sprints += counts[0];
                items += counts[1];
            }
        }

        // One summary line rather than one per project, to avoid burying the start-up log.
        if (seeded == 0) {
            log.info("AgileDemoSeeder — tous les projets ont déjà un board, ignoré.");
        } else {
            log.info("AgileDemoSeeder — {} projets alimentés : {} sprints, {} éléments de backlog.",
                     seeded, sprints, items);
        }
    }

    /**
     * True when created_by identifies a machine row (JPA auditing writes "system" when nobody
     * is logged in), so it may be replaced. null/MARKER cover rows from older seeder versions.
     */
    private boolean ours(String createdBy) {
        return createdBy == null || "system".equals(createdBy) || MARKER.equals(createdBy);
    }

    /**
     * True when the project has at least one live sprint a real person created — the whole
     * project is then skipped rather than mixed with generated sprints, which could overlap.
     */
    private boolean hasHandMadeBoard(Project project) {
        return sprintRepo.findActiveByProjectId(project.getId()).stream()
                .anyMatch(s -> !ours(s.getCreatedBy()));
    }

    /**
     * Deletes the rows this seeder wrote on a previous start, so the board can be rebuilt.
     * Real deleteAll (not the app's usual soft delete) since these rows are machine-only;
     * cards are deleted before sprints to satisfy the sprint_id foreign key, with a flush
     * after each so the DELETEs hit the DB before seed()'s INSERTs. Known limit: a hand-added
     * card on a generated sprint survives here but blocks deleting that sprint on the next
     * restart (foreign key violation), rolling back the whole run — not covered by
     * {@link #hasHandMadeBoard(Project)}, which only checks sprint authorship.
     */
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

    /**
     * Builds and saves the whole board of one project: {sprints written, cards written},
     * or {0, 0} when the project is refused (no/invalid dates).
     */
    private int[] seed(Project project) {
        // Showcase project's board is written out by hand instead; everything below is the generator.
        if (SHOWCASE_CODE.equals(project.getCode())) {
            return seedShowcase(project);
        }
        // No start date to anchor sprints on: refuse rather than NPE further down.
        LocalDate start = project.getStartDate();
        if (start == null) {
            return new int[]{0, 0};
        }
        // Open-ended contract: assume DEFAULT_MONTHS rather than refuse; a wrong guess only
        // shifts the sprint count slightly.
        LocalDate end = project.getEndDate() != null
                ? project.getEndDate()
                : start.plusMonths(DEFAULT_MONTHS);
        // Dates typed backwards or equal: refuse rather than silently generate sprints that
        // run past the project's end date (the DB's chk_sprint_dates check wouldn't catch it).
        if (!end.isAfter(start)) {
            return new int[]{0, 0};
        }

        // Seeded on the project id (not the clock) so the same project always gets the same
        // board on a fresh database — needed for report screenshots to stay reproducible.
        Random random = new Random(project.getId());
        // Read once and reused below so every sprint of this project compares against the same
        // "today" (avoids two sprints both reading as ACTIVE across a midnight boundary).
        LocalDate today = LocalDate.now();

        long weeks = ChronoUnit.WEEKS.between(start, end);
        // Sprint count clamped to [MIN_SPRINTS, MAX_SPRINTS]; see field comments for why.
        int count = (int) Math.min(MAX_SPRINTS, Math.max(MIN_SPRINTS, weeks / SPRINT_WEEKS));
        LocalDate seriesStart = anchor(start, end, today, count);

        List<Sprint> sprints = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Sprint i starts 3*i weeks after the first, so sprints follow with no gap.
            LocalDate from = seriesStart.plusWeeks((long) i * SPRINT_WEEKS);
            // End date is the day before the next sprint starts, so no day belongs to both.
            LocalDate to = from.plusWeeks(SPRINT_WEEKS).minusDays(1);

            // Status deduced from dates, never random: finished -> CLOSED, not begun -> PLANNED,
            // else -> ACTIVE (a sprint ending today is still ACTIVE since isBefore is strict).
            SprintStatus status = to.isBefore(today) ? SprintStatus.CLOSED
                                : from.isAfter(today) ? SprintStatus.PLANNED
                                : SprintStatus.ACTIVE;

            // Wraps the phase list if a project ever needed more than 5 sprints (can't happen today).
            String[] phase = PHASES[i % PHASES.length];
            sprints.add(Sprint.builder()
                    .project(project)
                    .name("Sprint " + (i + 1) + " — " + phase[0])
                    .goal(phase[1])
                    .startDate(from).endDate(to)
                    .status(status)
                    .build());
        }
        // Saved before cards are built: a card references its Sprint, which needs an id first.
        sprintRepo.saveAll(sprints);

        // May be empty; pick() handles that by leaving the card unassigned.
        List<User> team = teamMembers(project);
        // Random start position in ITEMS, then offset++ only moves forward so no title repeats.
        int offset = random.nextInt(ITEMS.length);
        // Shared across all sprints of this project so cards spread over the whole team.
        int turn = 0;
        List<BacklogItem> items = new ArrayList<>();

        for (Sprint sprint : sprints) {
            // 3 or 4 cards: floor of 3 lets statusIn() show all three columns, ceiling of 4
            // keeps total title usage under the 30-entry ITEMS pool.
            int perSprint = 3 + random.nextInt(2);          // three or four
            for (int i = 0; i < perSprint; i++) {
                items.add(item(project, sprint, nextTitle(offset++),
                               PRIORITIES[random.nextInt(PRIORITIES.length)],
                               statusIn(sprint, i, perSprint, random),
                               ESTIMATES[random.nextInt(ESTIMATES.length)],
                               // Last card of a planned sprint stays unassigned; turn++ only
                               // fires when somebody is actually picked, to keep round-robin balanced.
                               sprint.getStatus() == SprintStatus.PLANNED && i == perSprint - 1
                                       ? null : pick(team, turn++)));
            }
        }

        // Loose cards with no sprint: the product backlog.
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

    /**
     * Writes the showcase board exactly as SHOWCASE_SPRINTS/ITEMS describe it, no random
     * choice at all — even a seeded generator would drift if the title pool ever changed.
     */
    private int[] seedShowcase(Project project) {
        List<Sprint> sprints = new ArrayList<>();
        for (String[] row : SHOWCASE_SPRINTS) {
            sprints.add(Sprint.builder()
                    .project(project)
                    .name(row[0]).goal(row[1])
                    .startDate(LocalDate.parse(row[2]))
                    .endDate(LocalDate.parse(row[3]))
                    // Unlike the generator, this status is fixed text and does not follow the calendar.
                    .status(SprintStatus.valueOf(row[4]))
                    .build());
        }
        // Saved first: cards below reference these Sprint objects, which need an id.
        // saveAll preserves list order, which is what makes SHOWCASE_ITEMS' index meaningful.
        sprintRepo.saveAll(sprints);

        List<User> team = teamMembers(project);
        int turn = 0;                                        // shared round-robin, as in seed()
        List<BacklogItem> items = new ArrayList<>();

        for (String[] row : SHOWCASE_ITEMS) {
            // First column is the sprint's position in SHOWCASE_SPRINTS, or -1 for backlog.
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
     * Picks the first sprint's start date so the board is worth looking at: starting the
     * series on the contract's first day would leave an old project showing only CLOSED
     * sprints with nothing active. Not started yet -> anchor at contract start; already
     * finished -> anchor as late as the window allows; running -> center today's sprint.
     *
     * @param count how many sprints will be created, so the series still fits the window.
     */
    private LocalDate anchor(LocalDate start, LocalDate end, LocalDate today, int count) {
        long span = (long) count * SPRINT_WEEKS;
        // Latest possible first day: starting here makes the last sprint end with the contract.
        LocalDate latest = end.minusWeeks(span);
        // Window shorter than the series (MIN_SPRINTS forces >= 3 even on a short project):
        // no room to choose, so just start at the contract and let it overflow the end.
        if (latest.isBefore(start)) {
            return start;                                   // window too short to place freely
        }
        if (today.isBefore(start)) {
            return start;
        }
        if (today.isAfter(end)) {
            return latest;
        }
        // Center the sprint containing today in the series (count/2 sprints before it).
        LocalDate candidate = today.minusWeeks((long) (count / 2) * SPRINT_WEEKS);
        if (candidate.isBefore(start)) return start;
        if (candidate.isAfter(latest)) return latest;
        return candidate;
    }

    /**
     * The people really assigned to this project (may be empty). Reads the real team, not any
     * user, because BacklogItemService enforces the same check on real assignments; the filter
     * also drops a soft-deleted account so a card is never owned by someone who can't log in.
     */
    private List<User> teamMembers(Project project) {
        return teamRepo.findActiveByProjectId(project.getId()).stream()
                .map(TeamAssignment::getUser)
                .filter(u -> u != null && !u.isDeleted())
                .toList();
    }

    /**
     * Next team member, round-robin (not random, so the load looks evenly shared), or null
     * when the team is empty. Uses floorMod rather than % to stay safe if turn ever went negative.
     */
    private User pick(List<User> team, int turn) {
        return team.isEmpty() ? null : team.get(Math.floorMod(turn, team.size()));
    }

    /**
     * Board column for a card, deduced from its sprint's state rather than drawn at random
     * (a random status could put TODO in a sprint closed six months ago).
     *
     * @param index the position of the card inside its sprint, starting at 0
     * @param total how many cards this sprint holds (three or four)
     * @param random used only for the very last card of an active sprint
     */
    private BacklogItemStatus statusIn(Sprint sprint, int index, int total, Random random) {
        switch (sprint.getStatus()) {
            case CLOSED:
                return BacklogItemStatus.DONE;
            case PLANNED:
                return BacklogItemStatus.TODO;
            // ACTIVE: the sprint a demo actually shows, so cards are spread over all 3 columns.
            default:
                if (index == 0) return BacklogItemStatus.DONE;      // DONE column never empty
                // Math.max(2, total - 1) guarantees at least one IN_PROGRESS card even at total=3.
                if (index < Math.max(2, total - 1)) return BacklogItemStatus.IN_PROGRESS;
                // Only the last card is left to chance (still deterministic: random is seeded on project id).
                return random.nextBoolean() ? BacklogItemStatus.TODO : BacklogItemStatus.IN_PROGRESS;
        }
    }

    /**
     * Reads a title from the ITEMS pool, wrapping around via floorMod so an ever-increasing
     * offset never throws even if it somehow went negative.
     */
    private String nextTitle(int index) {
        return ITEMS[Math.floorMod(index, ITEMS.length)];
    }

    /**
     * Builds one BacklogItem in memory (unsaved); {@code sprint}/{@code assignee} may be null
     * (product backlog / unpicked). {@code new BigDecimal(days)} takes a String so the
     * NUMERIC(6,2) digits are stored exactly, avoiding double's binary rounding.
     *
     * <p>Note what this does NOT set: created_by, created_at, id, deleted — BaseEntity's JPA
     * auditing fills those at insert time, and created_by landing on "system" is exactly what
     * lets a later restart recognise these rows as its own (see {@link #ours(String)}).
     */
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

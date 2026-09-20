package com.pms.shared.config;

import com.pms.billing.entity.Avenant;
import com.pms.billing.entity.JalonFacturation;
import com.pms.billing.entity.JalonStatut;
import com.pms.billing.entity.Paiement;
import com.pms.billing.repository.AvenantRepository;
import com.pms.billing.repository.JalonFacturationRepository;
import com.pms.billing.repository.PaiementRepository;
import com.pms.governance.entity.*;
import com.pms.governance.repository.*;
import com.pms.kpi.entity.SnapshotKpi;
import com.pms.kpi.repository.SnapshotKpiRepository;
import com.pms.mission.entity.ComposanteMission;
import com.pms.mission.entity.Mission;
import com.pms.mission.entity.TypeComposante;
import com.pms.mission.repository.ComposanteMissionRepository;
import com.pms.mission.repository.MissionRepository;
import com.pms.project.entity.*;
import com.pms.project.repository.LigneDiRepository;
import com.pms.project.repository.ProjectRepository;
import com.pms.team.entity.TeamAssignment;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.Resource;
import com.pms.user.entity.Role;
import com.pms.user.entity.TccAnnuel;
import com.pms.user.entity.User;
import com.pms.user.repository.ResourceRepository;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.TccAnnuelRepository;
import com.pms.user.repository.UserRepository;
import com.pms.workload.entity.ChargeReelle;
import com.pms.workload.entity.PlanCharge;
import com.pms.workload.repository.ChargeReelleRepository;
import com.pms.workload.repository.PlanChargeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// One-shot ApplicationRunner (@Order 100, before EnterpriseDataSeeder/AgileDemoSeeder) that seeds
// a demo company into an empty database, writing through repositories directly (no HTTP request
// / logged-in user exists at start-up). Idempotent via the DEMO-RH-2025 sentinel project code;
// the DI (step 13) is seeded structure-only, no money values, per the 2026-07-05 decision.

/** Fills an empty database with the demo dataset (35 users, 15 projects, and all their rows).
 * ApplicationRunner + @Order sequences it before the other seeders; amounts use BigDecimal(String), never double, to avoid drift. */
// Needed so Spring Boot picks this ApplicationRunner up as a bean and calls it.
@Component
// Runs before EnterpriseDataSeeder (200) and AgileDemoSeeder (210), which need these projects
// to already exist; DataInitializer has no @Order so it runs last of all.
@Order(100)
// Lombok-generated constructor over every final field below, for constructor injection
// (fields stay final, and the class is easy to build by hand with mocks in tests).
@RequiredArgsConstructor
// Adds the `log` field used below — the only visibility into what happened at start-up.
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {

    // 19 repositories (18 for writing, roleRepo read-only — roles come from Flyway migrations)
    // plus pwdEncoder (BCrypt), so demo accounts are stored hashed exactly like real ones.
    private final UserRepository         userRepo;
    private final RoleRepository         roleRepo;
    private final ResourceRepository     resourceRepo;
    private final TccAnnuelRepository    tccRepo;
    private final ProjectRepository      projectRepo;
    private final TeamAssignmentRepository teamRepo;
    private final PlanChargeRepository   planRepo;
    private final ChargeReelleRepository chargeRepo;
    private final SnapshotKpiRepository  kpiRepo;
    private final MissionRepository      missionRepo;
    private final ComposanteMissionRepository composanteRepo;
    private final AvenantRepository      avenantRepo;
    private final JalonFacturationRepository jalonRepo;
    private final PaiementRepository     paiementRepo;
    private final RiskRepository         riskRepo;
    private final LivrableRepository     livrableRepo;
    private final PartiePrenanteRepository partieRepo;
    private final DemandeChangementRepository demandeRepo;
    private final LigneDiRepository      ligneDiRepo;
    private final PasswordEncoder        pwdEncoder;

    // Demo password for all accounts, published in docs/demo-accounts.md. Unlike DataInitializer
    // this seeder has no @ConditionalOnProperty switch to disable it on a real deployment.
    private static final String DEMO_PWD = "Demo@2026!";
    // Flag project code: if a live project already has it, the dataset exists — cheap indexed
    // check (uk_projects_code) without it every restart would try to re-insert everything.
    private static final String SENTINEL  = "DEMO-RH-2025";

    /** Generates the whole demo dataset in thirteen numbered steps, in FK order. One long method
     * rather than thirteen private ones because the steps share state (users, projects, budgets). */
    // @Transactional makes the whole generation one unit of work: a failure partway through rolls
    // back everything, including the sentinel project, instead of leaving a half-seeded database.
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Idempotence guard: looks only at live (non-deleted) projects, so soft-deleting the
        // sentinel would make the next restart regenerate the dataset (users are reused via user()).
        if (projectRepo.existsByCodeAndDeletedFalse(SENTINEL)) {
            log.info("DemoDataSeeder — jeu de données déjà présent, ignoré.");
            return;
        }

        log.info("DemoDataSeeder — génération du jeu de données de démonstration...");

        // Roles come from Flyway migrations, not created here — a role built by the seeder would
        // have an empty permission bag. Looked up by name only to link rows; access checks use permissions.
        Role roleDir  = role("DIRECTEUR");
        Role roleChef = role("CHEF_PROJET");
        Role roleDev  = role("DEVELOPPEUR");

        // Missing roles (e.g. a test schema without RBAC migrations) would otherwise fail on a
        // confusing NOT NULL constraint violation deep inside user(...); warn and bail out instead.
        if (roleDir == null || roleChef == null || roleDev == null) {
            log.warn("DemoDataSeeder — rôles RBAC introuvables, seeder ignoré.");
            return;
        }

        // Hashed once and reused for all accounts: BCrypt is deliberately slow, and hashing
        // per-user would add a visible start-up pause. All accounts sharing one hash is fine for demo data.
        String pwd = pwdEncoder.encode(DEMO_PWD);

        // ── 1. Users ─────────────────────────────────────────────────────────────

        // 35 accounts across the three roles; user() below reuses an existing account by e-mail,
        // so this block is safe to run twice.
        // 5 Directeurs
        User dir1 = user("Sami",     "Mansouri",   "sami.mansouri@demo.pms",   pwd, roleDir);
        User dir2 = user("Nadia",    "Belhadj",    "nadia.belhadj@demo.pms",   pwd, roleDir);
        User dir3 = user("Karim",    "Trabelsi",   "karim.trabelsi@demo.pms",  pwd, roleDir);
        User dir4 = user("Leila",    "Boughattas", "leila.boughattas@demo.pms",pwd, roleDir);
        User dir5 = user("Mehdi",    "Gharbi",     "mehdi.gharbi@demo.pms",    pwd, roleDir);

        // 8 Chefs de projet
        User cp1  = user("Yasmine",  "Ayari",      "yasmine.ayari@demo.pms",   pwd, roleChef);
        User cp2  = user("Omar",     "Saidi",      "omar.saidi@demo.pms",      pwd, roleChef);
        User cp3  = user("Inès",     "Hamdi",      "ines.hamdi@demo.pms",      pwd, roleChef);
        User cp4  = user("Bilal",    "Ferchichi",  "bilal.ferchichi@demo.pms", pwd, roleChef);
        User cp5  = user("Rim",      "Chekir",     "rim.chekir@demo.pms",      pwd, roleChef);
        User cp6  = user("Hichem",   "Dridi",      "hichem.dridi@demo.pms",    pwd, roleChef);
        User cp7  = user("Amira",    "Khelil",     "amira.khelil@demo.pms",    pwd, roleChef);
        User cp8  = user("Zied",     "Mezni",      "zied.mezni@demo.pms",      pwd, roleChef);

        // 22 Développeurs
        User[] devs = {
            user("Ahmed",    "Ben Ali",    "ahmed.benali@demo.pms",    pwd, roleDev),
            user("Fatma",    "Jemli",      "fatma.jemli@demo.pms",     pwd, roleDev),
            user("Walid",    "Souissi",    "walid.souissi@demo.pms",   pwd, roleDev),
            user("Mariem",   "Chaari",     "mariem.chaari@demo.pms",   pwd, roleDev),
            user("Anas",     "Bchini",     "anas.bchini@demo.pms",     pwd, roleDev),
            user("Salma",    "Hosni",      "salma.hosni@demo.pms",     pwd, roleDev),
            user("Youssef",  "Tlili",      "youssef.tlili@demo.pms",   pwd, roleDev),
            user("Rania",    "Sfar",       "rania.sfar@demo.pms",      pwd, roleDev),
            user("Maher",    "Baccar",     "maher.baccar@demo.pms",    pwd, roleDev),
            user("Khouloud", "Jlassi",     "khouloud.jlassi@demo.pms", pwd, roleDev),
            user("Seifeddine","Nasr",      "seifeddine.nasr@demo.pms", pwd, roleDev),
            user("Asma",     "Rebai",      "asma.rebai@demo.pms",      pwd, roleDev),
            user("Hamza",    "Ouali",      "hamza.ouali@demo.pms",     pwd, roleDev),
            user("Imen",     "Zarrouk",    "imen.zarrouk@demo.pms",    pwd, roleDev),
            user("Firas",    "Hadjkacem",  "firas.hadjkacem@demo.pms", pwd, roleDev),
            user("Nour",     "Maaloul",    "nour.maaloul@demo.pms",    pwd, roleDev),
            user("Tarek",    "Mejri",      "tarek.mejri@demo.pms",     pwd, roleDev),
            user("Ghada",    "Bejaoui",    "ghada.bejaoui@demo.pms",   pwd, roleDev),
            user("Slim",     "Ammar",      "slim.ammar@demo.pms",      pwd, roleDev),
            user("Dorra",    "Hadj",       "dorra.hadj@demo.pms",      pwd, roleDev),
            user("Anis",     "Chebbi",     "anis.chebbi@demo.pms",     pwd, roleDev),
            user("Sonia",    "Ghedira",    "sonia.ghedira@demo.pms",   pwd, roleDev),
        };

        // ── 2. Resources + TccAnnuel ─────────────────────────────────────────────

        // Fixed order (directors, then PMs, then devs) — load-bearing: rates[]/tccs[] below are
        // read by the same index.
        List<User> allUsers = new ArrayList<>();
        allUsers.add(dir1); allUsers.add(dir2); allUsers.add(dir3);
        allUsers.add(dir4); allUsers.add(dir5);
        allUsers.add(cp1); allUsers.add(cp2); allUsers.add(cp3); allUsers.add(cp4);
        allUsers.add(cp5); allUsers.add(cp6); allUsers.add(cp7); allUsers.add(cp8);
        for (User d : devs) allUsers.add(d);

        List<Resource> resources = new ArrayList<>();
        // rates[i]/tccs[i] are the per-user daily rate and TCC coefficient (a fraction, not a
        // percent — loaded day cost = dailyRate * (1 + tccRate)). Only 34 entries for 35 users; Math.min below clamps.
        BigDecimal[] rates = {
            bd("650"), bd("700"), bd("680"), bd("720"), bd("660"),   // dirs
            bd("580"), bd("600"), bd("570"), bd("590"), bd("610"),   // chefs 1-5
            bd("620"), bd("560"), bd("595"), bd("608"),               // chefs 6-8, dev1
            bd("480"), bd("500"), bd("520"), bd("490"), bd("510"),   // devs
            bd("475"), bd("530"), bd("460"), bd("515"), bd("505"),
            bd("488"), bd("525"), bd("472"), bd("498"), bd("535"),
            bd("465"), bd("512"), bd("478"), bd("520"), bd("492")
        };
        BigDecimal[] tccs = {
            bd("0.2800"), bd("0.2900"), bd("0.2750"), bd("0.3000"), bd("0.2850"),
            bd("0.2600"), bd("0.2700"), bd("0.2650"), bd("0.2550"), bd("0.2700"),
            bd("0.2600"), bd("0.2550"), bd("0.2700"), bd("0.2500"),
            bd("0.2400"), bd("0.2500"), bd("0.2550"), bd("0.2450"), bd("0.2500"),
            bd("0.2400"), bd("0.2550"), bd("0.2350"), bd("0.2500"), bd("0.2450"),
            bd("0.2420"), bd("0.2530"), bd("0.2380"), bd("0.2470"), bd("0.2560"),
            bd("0.2360"), bd("0.2510"), bd("0.2390"), bd("0.2520"), bd("0.2440")
        };

        // Builds one Resource plus three TccAnnuel rows (2023-2025): rates are renegotiated
        // yearly (spec F-AFF-13 §6.3 rule 4), and KpiService prices each charge line by its own year.
        for (int i = 0; i < allUsers.size(); i++) {
            User u = allUsers.get(i);
            // Clamps index: 35 users but only 34 rates/tccs entries, else ArrayIndexOutOfBoundsException.
            BigDecimal rate = rates[Math.min(i, rates.length - 1)];
            BigDecimal tcc  = tccs[Math.min(i, tccs.length - 1)];
            // staffingStart predates the oldest project so the workload screens don't reject demo rows.
            // staffingEnd stays unset (still available) rather than a far-future date that reads as real.
            Resource res = resourceRepo.save(Resource.builder()
                .user(u)
                .dailyRate(rate)
                .tccRate(tcc)
                .staffingStart(LocalDate.of(2022, 1, 1))
                .build());
            resources.add(res);
            // 2023/2025 rates shift from the base year so the same man-day costs differently by
            // year. setScale matches column precision (NUMERIC(10,2)/(5,4)); V21's unique index forbids repeating a year.
            for (int yr = 2023; yr <= 2025; yr++) {
                BigDecimal adjRate = rate.multiply(bd(yr == 2023 ? "0.95" : yr == 2024 ? "1.00" : "1.05")).setScale(2, java.math.RoundingMode.HALF_UP);
                BigDecimal adjTcc  = tcc.add(bd(yr == 2023 ? "-0.0100" : yr == 2024 ? "0.0000" : "0.0100")).setScale(4, java.math.RoundingMode.HALF_UP);
                tccRepo.save(TccAnnuel.builder()
                    .resource(res)
                    .annee(yr)
                    .dailyRate(adjRate)
                    .tccRate(adjTcc)
                    .build());
            }
        }

        // ── 3. Projects ──────────────────────────────────────────────────────────

        // 15 projects written out individually (not looped), covering all five ProjectStatus
        // values since several screens (active list, workload, KPI steps) behave differently per status.
        // Also varies BusinessModel (SEUL/GROUPEMENT) and EngagementType (FORFAIT/REGIE);
        // margeNetteVendue is the fallback margin used only when a project has no DI lines.
        Project p01 = project("DEMO-RH-2025",     "Plateforme RH Digitale",
            "Dématérialisation des processus RH et gestion des talents",
            ProjectStatus.ACTIVE,    dir1, cp1, "Ministère de la Fonction Publique", "Banque Mondiale",
            "2025-01-15", "2026-06-30", "850000", BusinessModel.SEUL,   EngagementType.FORFAIT, bd("0.38"));
        Project p02 = project("DEMO-SI-2024",     "Système d'Information Intégré",
            "Refonte du SI central avec ERP et modules métier",
            ProjectStatus.ACTIVE,    dir1, cp2, "Groupe Poulina",       null,
            "2024-06-01", "2025-12-31", "1200000", BusinessModel.SEUL,  EngagementType.FORFAIT, bd("0.35"));
        Project p03 = project("DEMO-MOBIL-2025",  "Application Mobile Citoyen",
            "Développement d'une app mobile de services publics",
            ProjectStatus.ACTIVE,    dir2, cp3, "Mairie de Tunis",      "GIZ",
            "2025-03-01", "2026-02-28", "420000", BusinessModel.SEUL,   EngagementType.REGIE,   bd("0.40"));
        Project p04 = project("DEMO-BI-2025",     "Datawarehouse & BI",
            "Mise en place d'un entrepôt de données et tableaux de bord",
            ProjectStatus.ACTIVE,    dir2, cp4, "Banque de l'Habitat",  null,
            "2025-02-01", "2026-01-31", "650000", BusinessModel.GROUPEMENT, EngagementType.FORFAIT, bd("0.36"));
        Project p05 = project("DEMO-ERP-2024",    "Déploiement ERP Manufacturing",
            "Implémentation d'un ERP pour usine de fabrication",
            ProjectStatus.ACTIVE,    dir3, cp5, "SOTACIB",              null,
            "2024-09-01", "2025-08-31", "980000", BusinessModel.SEUL,   EngagementType.FORFAIT, bd("0.33"));
        Project p06 = project("DEMO-CYBER-2025",  "Audit & Sécurité Informatique",
            "Audit de sécurité, tests de pénétration et mise en conformité",
            ProjectStatus.ACTIVE,    dir3, cp6, "BIAT",                 null,
            "2025-04-01", "2025-12-31", "310000", BusinessModel.SEUL,   EngagementType.REGIE,   bd("0.42"));
        Project p07 = project("DEMO-CLOUD-2025",  "Migration Infrastructure Cloud",
            "Migration de l'infrastructure on-premise vers le cloud hybride",
            ProjectStatus.ACTIVE,    dir4, cp7, "Tunisie Télécom",      null,
            "2025-05-01", "2026-04-30", "750000", BusinessModel.SEUL,   EngagementType.FORFAIT, bd("0.37"));
        Project p08 = project("DEMO-IOT-2025",    "Plateforme IoT Agriculture",
            "Système de monitoring agricole par capteurs IoT",
            ProjectStatus.ON_HOLD,   dir4, cp8, "APIA",                 "AFD",
            "2025-01-01", "2025-10-31", "380000", BusinessModel.GROUPEMENT, EngagementType.FORFAIT, bd("0.39"));
        Project p09 = project("DEMO-ARCH-2024",   "Refonte Architecture Microservices",
            "Migration monolithe vers architecture microservices",
            ProjectStatus.COMPLETED, dir5, cp1, "Orange Tunisie",       null,
            "2024-01-01", "2024-12-31", "560000", BusinessModel.SEUL,   EngagementType.FORFAIT, bd("0.34"));
        Project p10 = project("DEMO-GED-2024",    "Gestion Électronique Documents",
            "Déploiement solution GED pour institution financière",
            ProjectStatus.COMPLETED, dir5, cp2, "STB Bank",             null,
            "2024-03-01", "2024-11-30", "290000", BusinessModel.SEUL,   EngagementType.REGIE,   bd("0.41"));
        Project p11 = project("DEMO-DIGITAL-2025","Transformation Digitale PME",
            "Accompagnement à la transformation numérique d'un réseau de PME",
            ProjectStatus.ACTIVE,    dir1, cp3, "UTICA",                "BAD",
            "2025-06-01", "2026-05-31", "920000", BusinessModel.GROUPEMENT, EngagementType.REGIE, bd("0.36"));
        Project p12 = project("DEMO-E-GOV-2025",  "Portail e-Gouvernement",
            "Plateforme de services administratifs en ligne",
            ProjectStatus.ACTIVE,    dir2, cp4, "Présidence du Gouvernement", "Banque Mondiale",
            "2025-07-01", "2026-12-31", "1500000", BusinessModel.SEUL,  EngagementType.FORFAIT, bd("0.32"));
        Project p13 = project("DEMO-MAINT-2025",  "Maintenance Applicative",
            "Contrat de maintenance et support pour parc applicatif client",
            ProjectStatus.ACTIVE,    dir3, cp5, "Tunisair",             null,
            "2025-01-01", "2025-12-31", "240000", BusinessModel.SEUL,   EngagementType.REGIE,   bd("0.43"));
        Project p14 = project("DEMO-FORMA-2024",  "Formation & Transfert Compétences",
            "Programme de formation et conduite du changement",
            ProjectStatus.CANCELLED, dir4, cp6, "Ministère de l'Éducation", "UNESCO",
            "2024-09-01", "2025-03-31", "180000", BusinessModel.SEUL,   EngagementType.REGIE,   bd("0.45"));
        Project p15 = project("DEMO-DRAFT-2025",  "Nouveau Projet CRM",
            "Étude de faisabilité et conception d'une solution CRM secteur banque",
            ProjectStatus.DRAFT,     dir5, cp7, null, null,
            "2025-08-01", "2026-07-31", "430000", BusinessModel.SEUL,   EngagementType.FORFAIT, bd("0.38"));

        // Unmodifiable on purpose (a mistake fails fast); order matters — teams[]/projectDays[]
        // arrays below are read by the same index.
        List<Project> allProjects = List.of(p01,p02,p03,p04,p05,p06,p07,p08,p09,p10,p11,p12,p13,p14,p15);

        // ── 4. TeamAssignments ───────────────────────────────────────────────────

        // TeamAssignment rows for each project's developers (chefProjet is already an FK from
        // step 3). Teams overlap on purpose so the workload screen shows people split across several projects.
        User[][] teams = {
            {devs[0],devs[1],devs[2],devs[3],devs[4]},   // p01
            {devs[2],devs[5],devs[6],devs[7],devs[8]},   // p02
            {devs[0],devs[3],devs[9],devs[10]},            // p03
            {devs[1],devs[4],devs[11],devs[12],devs[13]}, // p04
            {devs[5],devs[6],devs[14],devs[15]},           // p05
            {devs[7],devs[16],devs[17]},                   // p06
            {devs[8],devs[9],devs[18],devs[19]},           // p07
            {devs[10],devs[20],devs[21]},                  // p08
            {devs[11],devs[12],devs[13],devs[14]},         // p09
            {devs[15],devs[16]},                           // p10
            {devs[17],devs[18],devs[19],devs[0]},          // p11
            {devs[1],devs[2],devs[20],devs[21],devs[3]},  // p12
            {devs[4],devs[5]},                             // p13
            {devs[6]},                                     // p14
            {devs[7],devs[8]},                             // p15
        };
        // roleInTeam is a free-text label for the team screen only — it grants no permission.
        String[] teamRoles = {"Développeur Backend","Développeur Frontend","Développeur Full-Stack",
            "Analyste fonctionnel","Testeur QA","Architecte","Consultant","Scrum Master"};

        for (int i = 0; i < allProjects.size(); i++) {
            Project prj = allProjects.get(i);
            User chef = prj.getChefProjet();
            // Also writes the PM into team_assignments (not redundant with chef_projet_id —
            // ADR-021's access check accepts either link) so the team list shows them among staffed people.
            if (chef != null) {
                teamRepo.save(TeamAssignment.builder()
                    .project(prj).user(chef).roleInTeam("Chef de Projet")
                    .startDate(prj.getStartDate()).endDate(prj.getEndDate())
                    .build());
            }
            // Defensive bound check: a 16th project without a matching teams[] row gets an
            // empty team instead of crashing.
            User[] members = i < teams.length ? teams[i] : new User[0];
            // Modulo cycles titles so a team larger than teamRoles doesn't throw ArrayIndexOutOfBoundsException.
            for (int j = 0; j < members.length; j++) {
                teamRepo.save(TeamAssignment.builder()
                    .project(prj).user(members[j])
                    .roleInTeam(teamRoles[j % teamRoles.length])
                    .startDate(prj.getStartDate()).endDate(prj.getEndDate())
                    .build());
            }
        }

        // ── 5. PlanCharge & ChargeReelle ─────────────────────────────────────────

        // PlanCharge = planned man-days per person/project/month; ChargeReelle = what was
        // actually charged once validated. The gap between them drives every KPI margin/consumption figure.
        // projectDays[i] is the monthly JH each team member charges on project i — the knob that
        // decides whether a project reads as profitable (more days charged means a smaller margin).
        double[] projectDays = {22.0, 14.0, 23.0, 20.0, 11.0, 13.0, 17.0, 24.0, 18.0, 10.0, 21.0, 15.0, 19.0, 18.0, 15.0};

        for (int i = 0; i < allProjects.size(); i++) {
            Project prj = allProjects.get(i);
            // DRAFT hasn't started and CANCELLED shouldn't keep producing costs, so both get no workload.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            // Same defensive bound as step 4; skip projects with no team to avoid an empty, wasted month loop.
            User[] members = i < teams.length ? teams[i] : new User[0];
            if (members.length == 0) continue;

            // Fallback defaults for missing project fields (never hit today, but a null would abort start-up).
            double baseDays = i < projectDays.length ? projectDays[i] : 18.0;
            // validator falls back to dir1 so a validated line is never left with no validator.
            User validator  = prj.getChefProjet() != null ? prj.getChefProjet() : dir1;

            LocalDate start  = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate end    = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2026, 12, 31);
            // Periods are always stored as the 1st of the month (convention); V7's unique index
            // on (project_id, user_id, period) depends on that consistency.
            LocalDate cursor = start.withDayOfMonth(1);

            while (!cursor.isAfter(end)) {
                for (User dev : members) {
                    // Small month-to-month wobble so chart curves aren't flat lines — cosmetic only.
                    double delta = (cursor.getMonthValue() % 3 == 0) ? 1.5 : (cursor.getMonthValue() % 3 == 1) ? -1.0 : 0.5;
                    // Locale.US forces a dot decimal separator — a French default locale would
                    // produce a comma and new BigDecimal(...) would throw NumberFormatException.
                    BigDecimal planned = bd(String.format(Locale.US, "%.2f", baseDays + delta));
                    // V7's chk_pc_days/chk_cr_days constraints cap days at 31/month; raising
                    // projectDays[] much further (with the wobble and correction factor) would exceed that.
                    planRepo.save(PlanCharge.builder()
                        .project(prj).user(dev).period(cursor).plannedDays(planned)
                        .build());

                    // Only past months get actuals (compared to LocalDate.now(), not a fixed
                    // date, so the demo stays alive next year); future months keep a plan with no charge.
                    if (cursor.isBefore(LocalDate.now())) {
                        // Correction factor picked deterministically from month+project index
                        // (not Random) so the seeded database rebuilds identically every time.
                        BigDecimal factor = bd(pickFromArray(
                            new String[]{"0.92","0.97","1.00","1.03","0.95","0.98","1.01","0.96"},
                            cursor.getMonthValue() + i + 1));
                        BigDecimal actual = planned.multiply(factor).setScale(2, java.math.RoundingMode.HALF_UP);
                        // Submitted/validated dates mimic the real cycle; atStartOfDay() converts date to timestamp.
                        // Not cosmetic: KpiService only reads lines with validatedAt IS NOT NULL (findValidatedByProjectId).
                        chargeRepo.save(ChargeReelle.builder()
                            .project(prj).user(dev).period(cursor).actualDays(actual)
                            .submittedAt(cursor.plusDays(24).atStartOfDay())
                            .validatedAt(cursor.plusDays(27).atStartOfDay())
                            .validatedBy(validator)
                            .build());
                    }
                }
                cursor = cursor.plusMonths(1);
            }
        }

        // ── 6. SnapshotKpi ───────────────────────────────────────────────────────

        // Monthly snapshot per project (budget, EAC, margin, EV%, days). Stored as history rather
        // than recomputed, since KpiService can't reconstruct what a revised plan looked like in
        // the past — this doesn't contradict the DI rule (derived on read): a snapshot is a dated observation, not a computed amount.
        String[] faits = {
            "Avancement conforme au planning. Livraisons Sprint 3 validées par le client.",
            "Léger retard sur module de reporting — plan de rattrapage en cours.",
            "Sprint 4 livré avec 2 jours d'avance. Satisfaction client élevée.",
            "Dérive de charge détectée sur le module intégration — analyse en cours.",
            "Jalons de facturation atteints. Client satisfait des livrables.",
            "Revue de code effectuée — 23 anomalies corrigées. Qualité améliorée.",
            "Réunion CODIR validée. Scope étendu par avenant +80 JH.",
        };

        for (Project prj : allProjects) {
            // DRAFT skipped (no history yet); CANCELLED kept here unlike step 5 — it did run for a while.
            if (prj.getStatus() == ProjectStatus.DRAFT) continue;
            LocalDate start = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate end   = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2025, 12, 31);
            LocalDate cursor = start.withDayOfMonth(1);
            int snap = 0;
            BigDecimal budget = prj.getInitialBudget() != null ? prj.getInitialBudget() : bd("500000");

            // Stays inside the project window and in the past — a future snapshot would mean
            // observing something that hasn't happened yet.
            while (!cursor.isAfter(end) && cursor.isBefore(LocalDate.now())) {
                // EV% progresses per snapshot, capped at 100 by Math.min — without the cap a
                // long project could report over 100% done (column would still accept it).
                double pct = Math.min(100.0, snap * 8.5 + 5.0);
                BigDecimal evPct       = bd(String.format(Locale.US, "%.2f", pct));
                BigDecimal budgetPlan  = budget.multiply(bd(String.format(Locale.US, "%.4f", pct / 100.0))).setScale(2, java.math.RoundingMode.HALF_UP);
                // Fixed 93%/7% split (not a computation) — the real margin is worked out by
                // KpiService from validated charge lines and resource rates.
                BigDecimal budgetConso = budgetPlan.multiply(bd("0.93")).setScale(2, java.math.RoundingMode.HALF_UP);
                BigDecimal marge       = budgetPlan.subtract(budgetConso);
                BigDecimal caProduction = budget.multiply(bd(String.format(Locale.US, "%.4f", pct / 100.0))).setScale(2, java.math.RoundingMode.HALF_UP);

                // EAC = 102% of budget (small overrun). caProduction = totalFacture (70%) + fae
                // (30%) — the identity the billing screen relies on. dateFinEstimee alternates on-time/15-days-late.
                kpiRepo.save(SnapshotKpi.builder()
                    .project(prj)
                    .snapshotDate(cursor)
                    .budgetPlanifie(budgetPlan)
                    .budgetConsome(budgetConso)
                    .eac(budget.multiply(bd("1.02")).setScale(2, java.math.RoundingMode.HALF_UP))
                    .marge(marge)
                    .tauxConsommation(bd(String.format(Locale.US, "%.4f", pct / 100.0)))
                    .evPct(evPct)
                    .deliveryPct(bd(String.format(Locale.US, "%.2f", Math.min(100.0, pct + 2.5))))
                    .consommeJh(bd(String.format(Locale.US, "%.2f", pct * 2.2)))
                    .rafJh(bd(String.format(Locale.US, "%.2f", (100.0 - pct) * 2.0)))
                    .deriveJh(bd(String.format(Locale.US, "%.2f", pct * 0.1)))
                    .caProduction(caProduction)
                    .totalFacture(caProduction.multiply(bd("0.70")).setScale(2, java.math.RoundingMode.HALF_UP))
                    .fae(caProduction.multiply(bd("0.30")).setScale(2, java.math.RoundingMode.HALF_UP))
                    .margeActuelle(marge)
                    .margeActuellePct(bd(String.format(Locale.US, "%.4f", 0.07)))
                    .dateFinEstimee(end.plusDays(snap % 2 == 0 ? 0 : 15))
                    .faitsMarquants(faits[snap % faits.length])
                    .build());
                snap++;
                cursor = cursor.plusMonths(1);
            }
        }

        // ── 7. Missions + ComposanteMission ──────────────────────────────────────

        // Missions (business trips) carry their cost lines (transport/accommodation/per diem) in
        // a separate ComposanteMission table, since the list of cost kinds isn't closed and one
        // mission can have several lines of the same kind; TypeComposante being an enum also rules out typos.
        String[] objets = {
            "Atelier de cadrage fonctionnel avec le client",
            "Présentation bilan de sprint et démo client",
            "Formation utilisateurs finaux module RH",
            "Audit technique infrastructure existante",
            "Réunion de validation livrables phase 2",
            "Séminaire de lancement projet",
            "Workshop conduite du changement",
            "Revue de sécurité et conformité RGPD",
            "Atelier de test d'acceptation utilisateur",
            "Réunion de clôture et retour d'expérience",
        };
        String[] lieux = {"Tunis","Sfax","Sousse","Monastir","Nabeul","Bizerte","Ariana","La Marsa"};

        // Only the first 11 projects get missions — p12-p15 stay empty so the empty-state screen shows too.
        List<Project> missionProjects = List.of(p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11);
        int mIdx = 0;
        for (Project prj : missionProjects) {
            // 3-5 missions per project, keyed off the project index for a deterministic rebuild.
            int nbMissions = 3 + (mIdx % 3); // 3-5 missions per project
            User[] members = mIdx < teams.length ? teams[mIdx] : new User[]{devs[0]};
            for (int m = 0; m < nbMissions; m++) {
                // First mission lands a month in (not on day one — a trip before anything is
                // framed isn't believable), then every two months. The `L` in `m * 2L` is habitual, not strictly required here.
                LocalDate mStart = (prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 6, 1))
                    .plusMonths(m * 2L + 1);
                // mEnd varies 2-4 days out (m % 3) so missions aren't all identical length.
                LocalDate mEnd   = mStart.plusDays(2 + m % 3);
                User mUser = members[m % members.length];
                Mission mission = missionRepo.save(Mission.builder()
                    .project(prj)
                    .user(mUser)
                    .objet(objets[(mIdx + m) % objets.length])
                    .lieu(lieux[(mIdx + m) % lieux.length])
                    .dateDebut(mStart)
                    .dateFin(mEnd)
                    .build());
                // Transport and accommodation always, per diem every other mission — ordinary
                // travel expenses, not company cost structure.
                composanteRepo.save(ComposanteMission.builder()
                    .mission(mission).typeComposante(TypeComposante.TRANSPORT)
                    .montant(bd(String.valueOf(80 + m * 15))).devise("TND")
                    .description("Transport aller-retour " + lieux[(mIdx + m) % lieux.length])
                    .build());
                composanteRepo.save(ComposanteMission.builder()
                    .mission(mission).typeComposante(TypeComposante.SEJOUR)
                    .montant(bd(String.valueOf(120 + m * 10))).devise("TND")
                    .description("Hébergement " + (mEnd.getDayOfYear() - mStart.getDayOfYear() + 1) + " nuit(s)")
                    .build());
                if (m % 2 == 0) {
                    composanteRepo.save(ComposanteMission.builder()
                        .mission(mission).typeComposante(TypeComposante.PERDIEM)
                        .montant(bd(String.valueOf(45 + m * 5))).devise("TND")
                        .description("Per diem repas d'affaires client")
                        .build());
                }
            }
            mIdx++;
        }

        // ── 8. Avenants + JalonFacturation + Paiements ───────────────────────────

        // JalonFacturation (billing milestone), Paiement (money received), Avenant (contract
        // amendment). Three milestone templates rotate so contracts aren't all the same shape;
        // each template's percentages sum to 100.
        String[][] jalonLabels = {
            {"Démarrage / Mise en place", "Fin spécifications", "Livraison R1 – Maquettes validées", "Livraison R2 – Prototype fonctionnel", "Recette finale", "Clôture projet"},
            {"Démarrage", "Livraison lot 1", "Livraison lot 2", "Recette", "Clôture"},
            {"Lancement", "Milestone 1", "Milestone 2", "Milestone 3", "Réception définitive"},
        };
        double[][] jalonPcts = {
            {10.0, 15.0, 20.0, 25.0, 20.0, 10.0},
            {15.0, 20.0, 25.0, 25.0, 15.0},
            {10.0, 25.0, 25.0, 25.0, 15.0},
        };

        // p08/p14/p15 excluded (on-hold/cancelled/draft) so the empty billing-screen state is shown too.
        List<Project> billingProjects = List.of(p01,p02,p03,p04,p05,p06,p07,p09,p10,p11,p12,p13);
        int bIdx = 0;
        for (Project prj : billingProjects) {
            BigDecimal budget = prj.getInitialBudget() != null ? prj.getInitialBudget() : bd("500000");
            LocalDate pStart  = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate pEnd    = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2025, 12, 31);

            // Cycles through the three templates; label and percentage arrays must be read by
            // the same index or shares would mismatch.
            int tmpl = bIdx % jalonLabels.length;
            String[] labels = jalonLabels[tmpl];
            double[] pcts   = jalonPcts[tmpl];
            // Milestones are placed proportionally (j+1)/n through the contract duration, not
            // by fixed months, so a short contract's last milestone still lands inside it.
            long durationDays = java.time.temporal.ChronoUnit.DAYS.between(pStart, pEnd);

            // Unused after being built (payments read the loop's `jalon` variable, not this
            // list) — harmless leftover.
            List<JalonFacturation> jalons = new ArrayList<>();
            for (int j = 0; j < labels.length; j++) {
                // (double) cast forces real division — without it int/int truncates and every
                // milestone but the last would land on the project's start date.
                double ratio = (double)(j + 1) / labels.length;
                LocalDate datePrevue = pStart.plusDays((long)(durationDays * ratio));
                // Fixed cutoff date (not LocalDate.now()) — unlike step 5's charge lines, this
                // split doesn't move over time.
                boolean isPast = datePrevue.isBefore(LocalDate.of(2025, 7, 1));
                JalonStatut statut = isPast ? JalonStatut.FACTURE : JalonStatut.PREVU;
                LocalDate dateFacture = isPast ? datePrevue.plusDays(7) : null;

                // pourcentage and montant are both stored so a figure stays explainable after a
                // budget revision. dateFacture null vs set is how the screen tells "planned" from "done".
                // pcts[j % pcts.length]: the loop counts on labels, so the modulo guards against
                // the two arrays drifting out of sync.
                JalonFacturation jalon = jalonRepo.save(JalonFacturation.builder()
                    .project(prj)
                    .label(labels[j])
                    .pourcentage(bd(String.format(Locale.US, "%.2f", pcts[j % pcts.length])))
                    .montant(budget.multiply(bd(String.format(Locale.US, "%.4f", pcts[j % pcts.length] / 100.0))).setScale(2, java.math.RoundingMode.HALF_UP))
                    .datePrevue(datePrevue)
                    .dateFacture(dateFacture)
                    .statut(statut)
                    .build());
                jalons.add(jalon);

                // Null-amount guard: paiements.montant_recu is NOT NULL, so a null here would
                // abort the transaction. Payment arrives 14 days after invoicing; reference
                // follows VIR-<code>-J<n>-<year> ("virement" = bank transfer).
                if (statut == JalonStatut.FACTURE && jalon.getMontant() != null) {
                    paiementRepo.save(Paiement.builder()
                        .jalon(jalon)
                        .montantRecu(jalon.getMontant())
                        .datePaiement(dateFacture.plusDays(14))
                        .reference("VIR-" + prj.getCode() + "-J" + (j + 1) + "-" + dateFacture.getYear())
                        .build());
                }
            }

            // Only some projects get an amendment (an exception in real life) — screens must
            // show both cases. CANCELLED is excluded since extending a stopped contract makes no sense.
            if (bIdx % 3 == 0 && prj.getStatus() != ProjectStatus.CANCELLED) {
                avenantRepo.save(Avenant.builder()
                    .project(prj)
                    .numero("AV-001")
                    .objet("Extension de périmètre — module complémentaire demandé par le client")
                    .montant(budget.multiply(bd("0.12")).setScale(2, java.math.RoundingMode.HALF_UP))
                    .workloadDays(bd("45.00"))
                    .dateAvenant(pStart.plusMonths(4))
                    .build());
            }
            if (bIdx % 5 == 0 && prj.getStatus() == ProjectStatus.ACTIVE) {
                avenantRepo.save(Avenant.builder()
                    .project(prj)
                    .numero("AV-002")
                    .objet("Avenant maintenance corrective post-recette — 6 mois supplémentaires")
                    .montant(budget.multiply(bd("0.08")).setScale(2, java.math.RoundingMode.HALF_UP))
                    .workloadDays(bd("25.00"))
                    .dateAvenant(pStart.plusMonths(8))
                    .build());
            }
            bIdx++;
        }

        // ── 9. Risks ─────────────────────────────────────────────────────────────

        // Risks use the same NiveauRisque enum for both probability and impact, which is what
        // lets the interface draw the probability/impact matrix. Three description sets rotate across projects.
        String[][] riskData = {
            {"Retard dans la validation des spécifications fonctionnelles par le client",
             "Risque de dépassement budgétaire lié à l'inflation des coûts sous-traitance",
             "Indisponibilité de ressources clés lors de la phase critique",
             "Résistance au changement des utilisateurs finaux",
             "Dépendance à un fournisseur unique pour les licences logicielles"},
            {"Instabilité de l'environnement de recette client",
             "Changements fréquents de périmètre en cours de projet",
             "Problèmes de performance sur la base de données legacy",
             "Risque de sécurité lié aux API exposées"},
            {"Non-disponibilité des données de migration dans les délais",
             "Incompatibilité de versions entre composants tiers",
             "Manque de compétences internes sur la technologie cible"},
        };
        NiveauRisque[] niveaux = {NiveauRisque.ELEVE, NiveauRisque.MOYEN, NiveauRisque.FAIBLE};
        StatutRisque[] statuts = {StatutRisque.OUVERT, StatutRisque.MITIGE, StatutRisque.FERME};

        int rIdx = 0;
        for (Project prj : allProjects) {
            // Risks only make sense on a live project (DRAFT hasn't started, CANCELLED can't be
            // hurt further). Step 10 makes the opposite choice for deliverables — skips DRAFT only.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            // Description sets rotate by project counter, so neighbouring projects differ.
            String[] rDescs = riskData[rIdx % riskData.length];
            // Impact is shifted by one against probability (rIdx + r + 1) so risks spread across
            // the matrix instead of lining up on the diagonal.
            for (int r = 0; r < rDescs.length; r++) {
                riskRepo.save(Risk.builder()
                    .project(prj)
                    .description(rDescs[r])
                    .probabilite(niveaux[(rIdx + r) % niveaux.length])
                    .impact(niveaux[(rIdx + r + 1) % niveaux.length])
                    .planMitigation("Mise en place d'un plan d'escalade et revue hebdomadaire avec le client.")
                    .statut(statuts[(rIdx + r) % statuts.length])
                    .build());
            }
            rIdx++;
        }

        // ── 10. Livrables ────────────────────────────────────────────────────────

        // Deliverables (docs/products the contract promises). Only DRAFT is skipped — unlike
        // risks, CANCELLED keeps its deliverables since they still existed before the stop.
        String[][] livrableData = {
            {"Cahier des charges validé","Maquettes IHM approuvées","Rapport d'audit infrastructure",
             "Prototype fonctionnel R1","Document architecture technique","Rapport de tests de charge",
             "Manuel utilisateur v1.0","Procès-verbal de recette"},
            {"Plan de projet détaillé","Spécifications techniques","Rapport d'analyse des risques",
             "Livraison partielle lot 1","Rapport d'avancement mensuel","Procès-verbal de clôture"},
            {"Étude de faisabilité","Rapport d'audit","Formation utilisateurs","Bilan de projet"},
        };
        StatutLivrable[] livStatuts = {StatutLivrable.LIVRE, StatutLivrable.EN_COURS, StatutLivrable.EN_ATTENTE, StatutLivrable.VALIDE};

        int lIdx = 0;
        for (Project prj : allProjects) {
            if (prj.getStatus() == ProjectStatus.DRAFT) continue;
            String[] livDescs = livrableData[lIdx % livrableData.length];
            LocalDate pStart  = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            for (int l = 0; l < livDescs.length; l++) {
                LocalDate echeance = pStart.plusMonths(l + 1);
                // Status derived from due date (not random), with fixed cutoff dates (not
                // LocalDate.now()) — same pattern as the billing milestones above.
                StatutLivrable sl;
                if (echeance.isBefore(LocalDate.of(2025, 1, 1))) sl = StatutLivrable.LIVRE;
                else if (echeance.isBefore(LocalDate.of(2025, 7, 1))) sl = livStatuts[l % 2 == 0 ? 0 : 1];
                else sl = livStatuts[2 + l % 2];

                livrableRepo.save(Livrable.builder()
                    .project(prj)
                    .titre(livDescs[l])
                    .description("Livrable contractuel — " + livDescs[l].toLowerCase())
                    .dateEcheance(echeance)
                    .statut(sl)
                    .build());
            }
            lIdx++;
        }

        // ── 11. Parties Prenantes ────────────────────────────────────────────────

        // Stakeholders (client-side people) reuse NiveauRisque for influence/interest, driving
        // the interface's influence/interest grid.

        // Local record (not a top-level class — used only here, and com.pms.governance would
        // wrongly suggest it's part of the domain model) keeps each template's five fields together.
        record PpTemplate(String fonction, String emailSuffix, String tel,
                          NiveauRisque influence, NiveauRisque interet) {}
        PpTemplate[] ppTemplates = {
            new PpTemplate("Directeur Général",        "dg",       "+216 70 000 001", NiveauRisque.ELEVE,  NiveauRisque.ELEVE),
            new PpTemplate("Directeur des Systèmes d'Information", "dsi", "+216 70 000 002", NiveauRisque.ELEVE,  NiveauRisque.MOYEN),
            new PpTemplate("Chef de Projet Client (MOA)", "moa",   "+216 70 000 003", NiveauRisque.MOYEN,  NiveauRisque.ELEVE),
            new PpTemplate("Représentant Bailleur de Fonds", "bailleur", "+216 70 000 004", NiveauRisque.MOYEN, NiveauRisque.MOYEN),
            new PpTemplate("Responsable Utilisateurs", "ru",        "+216 70 000 005", NiveauRisque.FAIBLE, NiveauRisque.ELEVE),
        };

        String[][] ppNoms = {
            {"Mohamed Ben Salem","Fatma Karray","Ali Hammouda","Zeineb Masmoudi","Riadh Chaabane"},
            {"Noureddine Slimane","Hajer Rouissi","Malek Ghariani","Sihem Ben Romdhane","Wissem Trabelsi"},
            {"Khaled Souissi","Ons Belhaj","Tarek Nasri","Myriam Abid","Saber Mejri"},
        };

        int ppIdx = 0;
        for (Project prj : allProjects) {
            // DRAFT has no client yet (p15's client is null, so there'd be no e-mail domain to
            // build) and CANCELLED has nobody left to contact.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            // 2-5 stakeholders per project; Math.min guards against nbPP exceeding ppTemplates later.
            int nbPP = 2 + (ppIdx % 4);
            String[] noms = ppNoms[ppIdx % ppNoms.length];
            // Builds an invented e-mail domain from the project code (e.g. "DEMO-RH-2025" ->
            // "rh-2025.tn"); nothing is ever sent to these addresses.
            String domaine = prj.getCode().toLowerCase().replace("demo-", "");
            for (int pp = 0; pp < Math.min(nbPP, ppTemplates.length); pp++) {
                PpTemplate t = ppTemplates[pp];
                partieRepo.save(PartiePrenante.builder()
                    .project(prj)
                    .nom(noms[pp % noms.length])
                    .fonction(t.fonction())
                    .email(t.emailSuffix() + "@" + domaine + ".tn")
                    .telephone(t.tel())
                    .influence(t.influence())
                    .interet(t.interet())
                    .build());
            }
            ppIdx++;
        }

        // ── 12. Demandes de Changement ───────────────────────────────────────────

        // Change requests cycle through all three statuses (APPROUVE/EN_ATTENTE/REJETE) per
        // project so the screen's colour-coding is all shown; dateDecision stays null while EN_ATTENTE.
        String[] dcTitres = {
            "Ajout module de reporting avancé",
            "Modification du workflow de validation",
            "Extension de périmètre — intégration SSO",
            "Révision des délais de livraison lot 3",
            "Ajout d'un tableau de bord temps réel",
            "Migration base de données vers PostgreSQL 16",
            "Ajout de notifications push mobile",
        };

        int dcIdx = 0;
        for (Project prj : allProjects) {
            // Same rule as risks: only live projects get change requests.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            // 1-3 requests per project (deterministic by counter); only a project with 3 shows all statuses.
            int nbDC = 1 + (dcIdx % 3);
            // PM stands in for the client (demandeur_id points at a User, and clients have no PMS account).
            // dir1 fallback matters here: demandeur_id is nullable = false, so a null would abort the transaction.
            User demandeur = prj.getChefProjet() != null ? prj.getChefProjet() : dir1;
            LocalDate pStart = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            for (int dc = 0; dc < nbDC; dc++) {
                // Priority follows position (ELEVEE/NORMALE/FAIBLE); the modulo lets a future
                // higher nbDC keep cycling instead of failing.
                PrioriteChangement prio = dc % 3 == 0 ? PrioriteChangement.ELEVEE : dc % 3 == 1 ? PrioriteChangement.NORMALE : PrioriteChangement.FAIBLE;
                // Status also follows position, guaranteeing all three screen colours are shown.
                StatutChangement sc = dc == 0 ? StatutChangement.APPROUVE : dc == 1 ? StatutChangement.EN_ATTENTE : StatutChangement.REJETE;
                // Requests land 2 months after start, then every 2 months; `2L` ensures the
                // multiplication is already a long for plusMonths.
                LocalDate dateDemande = pStart.plusMonths(2 + dc * 2L);
                demandeRepo.save(DemandeChangement.builder()
                    .project(prj)
                    .demandeur(demandeur)
                    .titre(dcTitres[(dcIdx + dc) % dcTitres.length])
                    .description("Demande de changement initiée suite à la revue de projet — impact estimé : " + (dc + 1) * 15 + " JH supplémentaires.")
                    .priorite(prio)
                    .statut(sc)
                    .dateDemande(dateDemande)
                    .dateDecision(sc != StatutChangement.EN_ATTENTE ? dateDemande.plusDays(10) : null)
                    .build());
            }
            dcIdx++;
        }

        // ── 13. LigneDi — STRUCTURE ONLY (no financial value) ───────────────────
        // SECURITY: LigneDi — structure only, no financial value (2026-07-05 decision). Only
        // section, display order, profile label and unit are written; every money column stays
        // null so the company types its own figures — seeding realistic costs/margins here would
        // expose the real cost structure. DevisInterneService derives every amount on read anyway (spec F-AFF-13).

        // The labels of the quote lines, grouped by section.
        String[][] diProfils = {
            // HONORAIRES - contractual profiles, charged in man-days (JH)
            {"Chef de Projet Senior","Architecte Solution","Développeur Backend Senior",
             "Développeur Frontend","Développeur Full-Stack","Analyste Fonctionnel",
             "Testeur QA","Consultant Fonctionnel"},
            // FRAIS - travel and accommodation
            {"Frais de déplacement mission Tunis","Per diem équipe projet",
             "Billet avion déplacement international","Frais hébergement"},
            // AUTRES_FRAIS - taxes, provisions, contract registration
            {"Provision pour risque (5 %)", "Droits d'enregistrement contrat",
             "Frais bancaires et garanties"},
        };
        // HONORAIRES = fee lines in man-days; FRAIS = travel/accommodation; AUTRES_FRAIS =
        // taxes/provisions, expressed as "%" (see unit below).

        // diSections[s] and diProfils[s] share an index — reordering one alone would misfile a section.
        SectionDi[] diSections = {SectionDi.HONORAIRES, SectionDi.FRAIS, SectionDi.AUTRES_FRAIS};

        // 9 of 15 projects get a quote skeleton; the rest show KpiService's fallback to the
        // manually typed Project.margeNetteVendue.
        List<Project> diProjects = List.of(p01, p02, p03, p04, p05, p07, p09, p11, p12);
        for (Project prj : diProjects) {
            for (int s = 0; s < diSections.length; s++) {
                String[] profils = diProfils[s];
                for (int l = 0; l < profils.length; l++) {
                    ligneDiRepo.save(LigneDi.builder()
                        .project(prj)
                        .section(diSections[s])
                        // ordre is 1-based display order — unset (default 0) would pile every line at the same rank.
                        .ordre(l + 1)
                        .profilContractuel(profils[l])
                        .ressourceProposee(null)   // no name: real staffing is company data
                        .ressourceRetenue(null)    // idem, the company fills it in itself
                        // unit is just a label — it's the section + tauxPourcentage that tell
                        // DevisInterneService how to price a line.
                        .unite(diSections[s] == SectionDi.AUTRES_FRAIS ? "%" : "H-Jour")
                        // Every financial column stays null on purpose (2026-07-05 decision) — see the note above.
                        .build());
                }
            }
        }

        // Only proof, on a headless server, that generation ran to completion rather than rolling back.
        log.info("DemoDataSeeder — terminé. {} utilisateurs, {} ressources, {} projets créés.",
            allUsers.size(), resources.size(), allProjects.size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Looks up a role by name, returning null (not throwing) so the caller can warn and stop
     * seeding cleanly when RBAC rows are missing (e.g. in a test schema). Not a security check. */
    private Role role(String name) {
        return roleRepo.findByName(name).orElse(null);
    }

    /** Finds the account by e-mail or creates it — makes the seeder idempotent (uk_users_email).
     * orElseGet (not orElse) avoids building a duplicate User on every call; firstLogin=false skips FirstLoginFilter. */
    private User user(String first, String last, String email, String pwd, Role role) {
        return userRepo.findActiveByEmailWithRole(email).orElseGet(() ->
            userRepo.save(User.builder()
                .firstName(first).lastName(last).email(email)
                .passwordHash(pwd).active(true).firstLogin(false)
                .role(role).build()));
    }

    /** Builds and saves one project. Dates/budget arrive as Strings so the 15 calls above read
     * as a table; archived is derived from status so COMPLETED/CANCELLED can't stay in the active list. */
    private Project project(String code, String name, String desc,
                            ProjectStatus status, User director, User chef,
                            String client, String funder,
                            String start, String end, String budget,
                            BusinessModel bm, EngagementType et, BigDecimal marge) {
        return projectRepo.save(Project.builder()
            .code(code).name(name).description(desc).status(status)
            .director(director).chefProjet(chef)
            .client(client).funder(funder)
            .startDate(LocalDate.parse(start))
            .endDate(LocalDate.parse(end))
            .initialBudget(new BigDecimal(budget))
            .businessModel(bm).engagementType(et)
            .margeNetteVendue(marge)
            .currency("TND").exchangeRateToTnd(BigDecimal.ONE)
            .archived(status == ProjectStatus.COMPLETED || status == ProjectStatus.CANCELLED)
            .build());
    }

    /** Short name for new BigDecimal(String) — always from a String, never a double, to avoid
     * float-rounding drift once hundreds of lines are summed. */
    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    /** Wraps an index into the array (Math.abs + modulo guard against negative/out-of-range index).
     * Used in step 5 for deterministic (non-Random) correction factors, so rebuilds are identical. */
    private static String pickFromArray(String[] arr, int idx) {
        return arr[Math.abs(idx) % arr.length];
    }
}

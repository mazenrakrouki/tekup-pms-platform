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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Start-up seeder that fills an empty database with a large "enterprise" demo dataset
 * (50 users, 30 projects, ~13 000 rows). Runs after Flyway (order 200, after DemoDataSeeder
 * at 100, before AgileDemoSeeder at 210 which needs these projects to exist).
 *
 * All figures are fixed literals, never random, so the dataset is reproducible across
 * restarts and screenshots stay matching. Section 13 (lignes_di) writes only structure -
 * every monetary column stays NULL, per the amended decision of 2026-07-05 (see
 * V23__devis_interne.sql): real costs/margins are confidential and typed in after
 * deployment, never committed to source. Idempotent via the ENT-CRM-2026 sentinel check.
 * All 50 accounts share the password Enterprise@ST2I2026! - acceptable only on a demo DB.
 */
@Component
@Order(200)
@RequiredArgsConstructor
@Slf4j
public class EnterpriseDataSeeder implements ApplicationRunner {

    // One repository per table this seeder fills, injected as final via the Lombok constructor.
    private final UserRepository              userRepo;
    private final RoleRepository              roleRepo;
    private final ResourceRepository          resourceRepo;
    private final TccAnnuelRepository         tccRepo;
    private final ProjectRepository           projectRepo;
    private final TeamAssignmentRepository    teamRepo;
    private final PlanChargeRepository        planRepo;
    private final ChargeReelleRepository      chargeRepo;
    private final SnapshotKpiRepository       kpiRepo;
    private final MissionRepository           missionRepo;
    private final ComposanteMissionRepository composanteRepo;
    private final AvenantRepository           avenantRepo;
    private final JalonFacturationRepository  jalonRepo;
    private final PaiementRepository          paiementRepo;
    private final RiskRepository              riskRepo;
    private final LivrableRepository         livrableRepo;
    private final PartiePrenanteRepository    partieRepo;
    private final DemandeChangementRepository demandeRepo;
    private final LigneDiRepository           ligneDiRepo;
    // BCrypt encoder from SecurityConfig; needed so password_hash matches what the login screen produces.
    private final PasswordEncoder             pwdEncoder;

    // Code of the first project created below; its presence means this seeder already ran once.
    private static final String SENTINEL_CODE = "ENT-CRM-2026";
    // Fake domain for the 50 generated accounts, distinct from the DEMO- accounts' @pms.local.
    private static final String ENT_DOMAIN    = "ent.st2i.tn";
    // One shared password for all 50 accounts so a demo can switch roles quickly. Demo-DB only.
    private static final String ENT_PWD       = "Enterprise@ST2I2026!";

    /**
     * Builds the whole enterprise dataset in 13 numbered sections, in FK order: users, then
     * resources/TCC, then projects, then everything hanging off a project. One long method
     * rather than several private ones because each section reuses locals built by the ones
     * before it, and a Spring singleton has no good place to keep that state as fields.
     *
     * @param args unused, required by ApplicationRunner.
     */
    @Override
    // Whole dataset in one DB transaction: a mid-run crash must not leave the sentinel project
    // committed with the rest of the dataset half-built (which the idempotency guard would then hide).
    @Transactional
    public void run(ApplicationArguments args) {
        // Idempotency guard: if the sentinel project already exists, this seeder already ran.
        if (projectRepo.existsByCodeAndDeletedFalse(SENTINEL_CODE)) {
            log.info("EnterpriseDataSeeder — données enterprise déjà présentes, ignoré.");
            return;
        }

        // Roles come from Flyway, not from here; users.role_id is NOT NULL so all three are needed.
        Role roleDir  = roleByName("DIRECTEUR");
        Role roleChef = roleByName("CHEF_PROJET");
        Role roleDev  = roleByName("DEVELOPPEUR");
        // Null happens in the test profile, where the RBAC seed migrations are not applied.
        // Warn and return rather than throw: this must not stop the whole application from starting.
        if (roleDir == null || roleChef == null || roleDev == null) {
            log.warn("EnterpriseDataSeeder — rôles RBAC introuvables, seeder ignoré.");
            return;
        }

        log.info("EnterpriseDataSeeder — génération du jeu de données enterprise (~13 000+ enreg.)...");
        // Hashed once and reused for all 50 users: BCrypt is deliberately slow, so hashing per-user
        // would add seconds to every start-up. All 50 rows therefore share one salt - acceptable
        // only because they already share the password, and only on a demo database.
        String pwd = pwdEncoder.encode(ENT_PWD);

        // ── 1. Users: 8 Directeurs ─────────────────────────────────────────────
        // 8 directors / 12 project managers / 30 developers - a realistic company shape, and
        // enough developers that the workload screens' pagination is actually exercised.
        User d1 = user("Habib",      "Zouari",       "habib.zouari@"      + ENT_DOMAIN, pwd, roleDir);
        User d2 = user("Mouna",      "Gueddiche",     "mouna.gueddiche@"   + ENT_DOMAIN, pwd, roleDir);
        User d3 = user("Rafik",      "Chaabane",      "rafik.chaabane@"    + ENT_DOMAIN, pwd, roleDir);
        User d4 = user("Sonia",      "Ben Amor",      "sonia.benamor@"     + ENT_DOMAIN, pwd, roleDir);
        User d5 = user("Nabil",      "Baccouche",     "nabil.baccouche@"   + ENT_DOMAIN, pwd, roleDir);
        User d6 = user("Lamia",      "Abdelkefi",     "lamia.abdelkefi@"   + ENT_DOMAIN, pwd, roleDir);
        User d7 = user("Taoufik",    "Ghazali",       "taoufik.ghazali@"   + ENT_DOMAIN, pwd, roleDir);
        User d8 = user("Chiraz",     "Mbarki",        "chiraz.mbarki@"     + ENT_DOMAIN, pwd, roleDir);

        // ── 1b. 12 Chefs de Projet ────────────────────────────────────────────
        User cp1  = user("Wafa",      "Cherif",        "wafa.cherif@"       + ENT_DOMAIN, pwd, roleChef);
        User cp2  = user("Saber",     "Hidouri",       "saber.hidouri@"     + ENT_DOMAIN, pwd, roleChef);
        User cp3  = user("Nadia",     "Zribi",         "nadia.zribi@"       + ENT_DOMAIN, pwd, roleChef);
        User cp4  = user("Issam",     "Ouerghi",       "issam.ouerghi@"     + ENT_DOMAIN, pwd, roleChef);
        User cp5  = user("Hanen",     "Jouini",        "hanen.jouini@"      + ENT_DOMAIN, pwd, roleChef);
        User cp6  = user("Mounir",    "Driss",         "mounir.driss@"      + ENT_DOMAIN, pwd, roleChef);
        User cp7  = user("Radhia",    "Kchaou",        "radhia.kchaou@"     + ENT_DOMAIN, pwd, roleChef);
        User cp8  = user("Fathi",     "Elleuch",       "fathi.elleuch@"     + ENT_DOMAIN, pwd, roleChef);
        User cp9  = user("Sirine",    "Zoghlami",      "sirine.zoghlami@"   + ENT_DOMAIN, pwd, roleChef);
        User cp10 = user("Adnen",     "Ben Jemaa",     "adnen.benjemaa@"    + ENT_DOMAIN, pwd, roleChef);
        User cp11 = user("Meriem",    "Karoui",        "meriem.karoui@"     + ENT_DOMAIN, pwd, roleChef);
        User cp12 = user("Riadh",     "Belhaj",        "riadh.belhaj@"      + ENT_DOMAIN, pwd, roleChef);

        // ── 1c. 30 Développeurs ───────────────────────────────────────────────
        // Array, not separate variables: devIdx in section 4 picks developers by position.
        // Trailing index comments keep devIdx's number lists readable against this array.
        User[] devs = {
            user("Yassine",   "Hamrouni",    "yassine.hamrouni@"    + ENT_DOMAIN, pwd, roleDev),  //  0
            user("Sabrine",   "Bouchrika",   "sabrine.bouchrika@"   + ENT_DOMAIN, pwd, roleDev),  //  1
            user("Mohamed",   "Ayachi",      "mohamed.ayachi@"      + ENT_DOMAIN, pwd, roleDev),  //  2
            user("Olfa",      "Essid",       "olfa.essid@"          + ENT_DOMAIN, pwd, roleDev),  //  3
            user("Adem",      "Khlif",       "adem.khlif@"          + ENT_DOMAIN, pwd, roleDev),  //  4
            user("Ines",      "Touil",       "ines.touil@"          + ENT_DOMAIN, pwd, roleDev),  //  5
            user("Salim",     "Meddeb",      "salim.meddeb@"        + ENT_DOMAIN, pwd, roleDev),  //  6
            user("Amina",     "Haddad",      "amina.haddad@"        + ENT_DOMAIN, pwd, roleDev),  //  7
            user("Tarek",     "Laabidi",     "tarek.laabidi@"       + ENT_DOMAIN, pwd, roleDev),  //  8
            user("Sana",      "Trabelsi",    "sana.trabelsi@"       + ENT_DOMAIN, pwd, roleDev),  //  9
            user("Bilel",     "Ferjani",     "bilel.ferjani@"       + ENT_DOMAIN, pwd, roleDev),  // 10
            user("Chaima",    "Nasraoui",    "chaima.nasraoui@"     + ENT_DOMAIN, pwd, roleDev),  // 11
            user("Zoubeir",   "Mabrouk",     "zoubeir.mabrouk@"     + ENT_DOMAIN, pwd, roleDev),  // 12
            user("Nesrine",   "Sassi",       "nesrine.sassi@"       + ENT_DOMAIN, pwd, roleDev),  // 13
            user("Khaled",    "Bouguerba",   "khaled.bouguerba@"    + ENT_DOMAIN, pwd, roleDev),  // 14
            user("Rim",       "Hammami",     "rim.hammami@"         + ENT_DOMAIN, pwd, roleDev),  // 15
            user("Lotfi",     "Saadaoui",    "lotfi.saadaoui@"      + ENT_DOMAIN, pwd, roleDev),  // 16
            user("Dorra",     "Bouzid",      "dorra.bouzid@"        + ENT_DOMAIN, pwd, roleDev),  // 17
            user("Hatem",     "Zidi",        "hatem.zidi@"          + ENT_DOMAIN, pwd, roleDev),  // 18
            user("Kawther",   "Lahmar",      "kawther.lahmar@"      + ENT_DOMAIN, pwd, roleDev),  // 19
            user("Nizar",     "Ben Fredj",   "nizar.benfredj@"      + ENT_DOMAIN, pwd, roleDev),  // 20
            user("Houda",     "Gafsi",       "houda.gafsi@"         + ENT_DOMAIN, pwd, roleDev),  // 21
            user("Ramzi",     "Marzouki",    "ramzi.marzouki@"      + ENT_DOMAIN, pwd, roleDev),  // 22
            user("Wiem",      "Baccari",     "wiem.baccari@"        + ENT_DOMAIN, pwd, roleDev),  // 23
            user("Oussama",   "Jebali",      "oussama.jebali@"      + ENT_DOMAIN, pwd, roleDev),  // 24
            user("Manel",     "Sellami",     "manel.sellami@"       + ENT_DOMAIN, pwd, roleDev),  // 25
            user("Amine",     "Bouali",      "amine.bouali@"        + ENT_DOMAIN, pwd, roleDev),  // 26
            user("Sarra",     "Toumi",       "sarra.toumi@"         + ENT_DOMAIN, pwd, roleDev),  // 27
            user("Moez",      "Kammoun",     "moez.kammoun@"        + ENT_DOMAIN, pwd, roleDev),  // 28
            user("Fatma",     "Riahi",       "fatma.riahi@"         + ENT_DOMAIN, pwd, roleDev),  // 29
        };

        // ── 2. Resources + TCC annuels (2024, 2025, 2026) ──────────────────────
        // Resource = today's daily rate + TCC overhead fraction; TccAnnuel freezes that pair
        // per year so recomputing an old project uses the rate that applied then, not today's.
        // Invented figures, written as strings for exact BigDecimal parsing; bands don't overlap
        // (directors > managers > developers), and each array is positional against allEntUsers.
        String[] dirRates  = {"820","840","800","830","810","850","780","790"};
        String[] dirTccs   = {"0.2800","0.2850","0.2750","0.2900","0.2820","0.2950","0.2700","0.2730"};
        String[] chefRates = {"640","660","620","650","630","670","610","655","625","645","635","615"};
        String[] chefTccs  = {"0.2550","0.2600","0.2500","0.2580","0.2520","0.2620","0.2480","0.2570","0.2510","0.2560","0.2530","0.2490"};
        // 30 dev rates
        String[] devRates  = {
            "490","510","480","520","475","505","495","515","470","500",
            "530","485","508","495","512","488","502","518","476","494",
            "522","486","514","492","506","498","516","484","508","474"
        };
        String[] devTccs   = {
            "0.2420","0.2460","0.2400","0.2480","0.2390","0.2450","0.2430","0.2470","0.2380","0.2440",
            "0.2490","0.2410","0.2460","0.2440","0.2470","0.2420","0.2450","0.2480","0.2395","0.2430",
            "0.2470","0.2415","0.2465","0.2435","0.2455","0.2445","0.2475","0.2405","0.2460","0.2385"
        };

        // Fixed order directors/managers/developers, matching the rate arrays glued below by position.
        List<User> allEntUsers = new ArrayList<>();
        allEntUsers.addAll(List.of(d1, d2, d3, d4, d5, d6, d7, d8));
        allEntUsers.addAll(List.of(cp1, cp2, cp3, cp4, cp5, cp6, cp7, cp8, cp9, cp10, cp11, cp12));
        for (User dv : devs) allEntUsers.add(dv);

        // Glue the three rate arrays into one 50-slot array (0..7 dirs, 8..19 managers, 20..49 devs).
        // Block sizes here must stay in sync with the counts added to allEntUsers above.
        String[] allRates = new String[50];
        String[] allTccs  = new String[50];
        System.arraycopy(dirRates,  0, allRates, 0,  8);
        System.arraycopy(chefRates, 0, allRates, 8,  12);
        System.arraycopy(devRates,  0, allRates, 20, 30);
        System.arraycopy(dirTccs,   0, allTccs,  0,  8);
        System.arraycopy(chefTccs,  0, allTccs,  8,  12);
        System.arraycopy(devTccs,   0, allTccs,  20, 30);

        // One Resource per person, then three TccAnnuel rows per Resource: 50 + 150 rows.
        // entResources is filled but never read again (dead weight, left as-is: comments-only pass).
        List<Resource> entResources = new ArrayList<>();
        for (int i = 0; i < allEntUsers.size(); i++) {
            BigDecimal rate = bd(allRates[i]);
            BigDecimal tcc  = bd(allTccs[i]);
            // The returned entity carries the generated id the TccAnnuel rows below need as FK.
            Resource res = resourceRepo.save(Resource.builder()
                .user(allEntUsers.get(i))
                .dailyRate(rate)
                .tccRate(tcc)
                // Before the earliest project start, so no timesheet can fall outside the staffing window.
                .staffingStart(LocalDate.of(2023, 1, 1))
                // Null = still staffed today, which is what all 50 people should show in the demo.
                .build());
            entResources.add(res);
            // TCC annuels pour 2024, 2025, 2026
            // yearMults/yearAdj give a rising trend (2024 cheaper, 2026 dearer) so the yearly
            // comparison screens show real movement and the "frozen per year" design is visible.
            String[] yearMults = {"0.97", "1.00", "1.04"};
            String[] yearAdj   = {"-0.0100", "0.0000", "0.0120"};
            for (int y = 0; y < 3; y++) {
                int annee = 2024 + y;
                // NUMERIC(10,2): round here explicitly rather than let the DB/driver do it silently.
                BigDecimal adjRate = rate.multiply(bd(yearMults[y])).setScale(2, RoundingMode.HALF_UP);
                // NUMERIC(5,4)
                BigDecimal adjTcc  = tcc.add(bd(yearAdj[y])).setScale(4, RoundingMode.HALF_UP);
                // One row per resource per year (2024/2025/2026), matching uk_tcc_annuel_resource_annee.
                tccRepo.save(TccAnnuel.builder()
                    .resource(res).annee(annee)
                    .dailyRate(adjRate).tccRate(adjTcc)
                    .build());
            }
        }

        // ── 3. Projects (30) ──────────────────────────────────────────────────
        // The spine of the dataset: everything after this section hangs off these rows.
        // Statuses split 12/6/4/3/5 across ACTIVE/COMPLETED/ON_HOLD/CANCELLED/DRAFT on purpose,
        // since later sections branch per status (e.g. DRAFT skips timesheets and KPI, only
        // ACTIVE/COMPLETED get a DI structure).
        // Each call goes through the private proj(...) helper (20 positional params: code, name,
        // description, status, director, chef, client, funder, start, end, budget, business
        // model, engagement type, sold net margin, contract id, currency, exchange rate,
        // licence/subcontracting budget, sold workload days, warranty workload days) - the
        // vertical alignment below is what makes a wrong value stand out by eye.
        // funder is the co-financing institution (null when the client alone funds it); SEUL vs
        // GROUPEMENT is solo vs consortium delivery; FORFAIT is fixed-price (company carries
        // over-run risk) vs REGIE time-and-materials; marge is the net margin sold as a fraction;
        // currency/exchange rate let contracts in EUR be compared in TND (rate 3.30); warrantyWl
        // is always 5% of soldWl. Clients are real organisations, contracts/budgets are invented.
        // ── ACTIVE (12) ───────────────────────────────────────────────────────
        Project p01 = proj("ENT-CRM-2026",      "Plateforme CRM Bancaire Nouvelle Génération",
            "Migration et refonte complète du CRM de la Banque Centrale. Architecture microservices, API-first, intégration CBS et moteur de scoring client.",
            ProjectStatus.ACTIVE, d1, cp1, "Banque Centrale de Tunisie", null,
            "2025-09-01", "2027-03-31", "1800000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.38"),
            "BCT-2025-CRM-001", "EUR", bd("3.30"), bd("180000"), bd("1250.00"), bd("62.50"));

        Project p02 = proj("ENT-ERP-DIST-2026",  "ERP Distribution & Logistique Intégrée",
            "Déploiement d'un ERP complet pour le réseau de distribution national. Modules ventes, achats, stocks, finance, RH et BI intégrés.",
            ProjectStatus.ACTIVE, d1, cp2, "SFBT Distribution", null,
            "2026-01-15", "2027-06-30", "950000", BusinessModel.GROUPEMENT, EngagementType.FORFAIT, bd("0.35"),
            "SFBT-2026-ERP-001", "TND", bd("1.00"), bd("95000"),  bd("780.00"), bd("39.00"));

        Project p03 = proj("ENT-SIH-2026",       "Système d'Information Hospitalier Régional",
            "Construction du SIH pour les hôpitaux de la région Centre. Dossier patient numérique, imagerie médicale, pharmacie, facturation assurance.",
            ProjectStatus.ACTIVE, d2, cp3, "Ministère de la Santé", "OMS / Banque Africaine de Développement",
            "2026-03-01", "2028-02-28", "2100000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.33"),
            "MS-2026-SIH-003", "EUR", bd("3.30"), bd("210000"), bd("1800.00"), bd("90.00"));

        Project p04 = proj("ENT-FINTECH-2026",   "Application Mobile Fintech – Paiement Instantané",
            "Développement d'une super-app de paiement mobile conforme à la réglementation BCT : transferts P2P, QR code, paiement marchand, portefeuille digital.",
            ProjectStatus.ACTIVE, d2, cp4, "Enda Tamweel", null,
            "2025-11-01", "2026-10-31", "680000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.40"),
            "ENDA-2025-FIN-001", "TND", bd("1.00"), bd("68000"),  bd("620.00"), bd("31.00"));

        Project p05 = proj("ENT-SCM-2026",       "Supply Chain Management Industrie Agroalimentaire",
            "Mise en place d'une plateforme SCM pour piloter la chaîne d'approvisionnement : prévisions demand, gestion fournisseurs, traçabilité produits.",
            ProjectStatus.ACTIVE, d3, cp5, "Délice Danone", null,
            "2025-10-01", "2026-09-30", "1200000", BusinessModel.GROUPEMENT, EngagementType.FORFAIT, bd("0.36"),
            "DD-2025-SCM-001", "EUR", bd("3.30"), bd("120000"), bd("980.00"), bd("49.00"));

        Project p06 = proj("ENT-ELEC-2026",      "Système de Gestion Électorale Nationale",
            "Plateforme sécurisée pour la gestion des listes électorales, des bureaux de vote et du dépouillement en temps réel. Conformité ISIE.",
            ProjectStatus.ACTIVE, d3, cp6, "ISIE – Instance Supérieure Indépendante pour les Élections", "Union Européenne",
            "2026-02-01", "2026-11-30", "3500000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.30"),
            "ISIE-2026-ELEC-001", "EUR", bd("3.30"), bd("350000"), bd("2800.00"), bd("140.00"));

        Project p07 = proj("ENT-INFRA-2026",     "Infrastructure Cloud Souverain Gouvernemental",
            "Construction d'un cloud gouvernemental hybride (IaaS/PaaS) pour héberger les services de l'État. Sécurité souveraine, haute disponibilité, reprise après sinistre.",
            ProjectStatus.ACTIVE, d4, cp7, "Agence Nationale de la Sécurité Informatique", null,
            "2026-04-01", "2028-03-31", "2800000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.32"),
            "ANSI-2026-CLOUD-001", "EUR", bd("3.30"), bd("280000"), bd("2400.00"), bd("120.00"));

        Project p08 = proj("ENT-TRACE-2026",     "Traçabilité Chaîne Agroalimentaire par QR Code",
            "Solution de traçabilité de la ferme à l'assiette pour produits agroalimentaires certifiés. QR codes, blockchain légère, conformité HACCP.",
            ProjectStatus.ACTIVE, d4, cp8, "GIFruits – Groupement Interprofessionnel des Fruits", "GIZ",
            "2026-01-01", "2027-01-31", "750000", BusinessModel.GROUPEMENT, EngagementType.FORFAIT, bd("0.39"),
            "GIF-2026-TRC-001", "TND", bd("1.00"), bd("75000"),  bd("650.00"), bd("32.50"));

        Project p09 = proj("ENT-SOC-2026",       "Centre Opérations Sécurité (SOC) & Cybersécurité",
            "Déploiement d'un SOC managé (SIEM, SOAR, CTI) et audit de conformité ISO 27001. Formation des équipes internes SSI.",
            ProjectStatus.ACTIVE, d5, cp9, "Banque Internationale Arabe de Tunisie", null,
            "2025-12-01", "2026-11-30", "480000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.42"),
            "BIAT-2025-SOC-001", "TND", bd("1.00"), bd("48000"),  bd("420.00"), bd("21.00"));

        Project p10 = proj("ENT-EDUC-2026",      "Digitalisation du Système Éducatif National",
            "ENT (Espace Numérique de Travail) pour 1,3 million d'élèves : Gestion établissements, bulletins numériques, ressources pédagogiques, communication parents-école.",
            ProjectStatus.ACTIVE, d5, cp10, "Ministère de l'Éducation", "Banque Mondiale / AFD",
            "2026-06-01", "2029-05-31", "4200000", BusinessModel.GROUPEMENT, EngagementType.FORFAIT, bd("0.28"),
            "ME-2026-ENT-001", "EUR", bd("3.30"), bd("420000"), bd("3500.00"), bd("175.00"));

        Project p11 = proj("ENT-TELEMEDE-2026",  "Télémédecine & Santé Digitale",
            "Plateforme de téléconsultation sécurisée, partage dossiers médicaux inter-établissements, suivi patients chroniques, ordonnances électroniques.",
            ProjectStatus.ACTIVE, d6, cp11, "CNAM – Caisse Nationale d'Assurance Maladie", "BAD",
            "2026-05-01", "2027-04-30", "920000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.35"),
            "CNAM-2026-TMD-001", "TND", bd("1.00"), bd("92000"),  bd("810.00"), bd("40.50"));

        Project p12 = proj("ENT-DEVOPS-2026",    "Transformation DevOps & CI/CD Bancaire",
            "Accompagnement DevOps pour une banque : pipelines CI/CD, IaC Terraform, observabilité, sécurité shift-left, formation des équipes (50+ devs).",
            ProjectStatus.ACTIVE, d6, cp12, "STB – Société Tunisienne de Banque", null,
            "2026-03-15", "2027-03-14", "380000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.43"),
            "STB-2026-DEVOPS-001", "TND", bd("1.00"), bd("38000"),  bd("340.00"), bd("17.00"));

        // ── COMPLETED (6) ─────────────────────────────────────────────────────
        // Delivered and closed: dates are in the past, deliverables all VALIDE/LIVRE, archived = true.
        Project p13 = proj("ENT-BSS-2024",       "Refonte Billing System Support Télécom",
            "Refonte complète du BSS d'un opérateur télécom national : facturation convergente, gestion abonnés, portail self-care, intégration CRM.",
            ProjectStatus.COMPLETED, d7, cp1, "Tunisie Télécom", null,
            "2023-06-01", "2025-05-31", "1600000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.34"),
            "TT-2023-BSS-001", "TND", bd("1.00"), bd("160000"), bd("1400.00"), bd("70.00"));

        Project p14 = proj("ENT-GRC-2025",       "Plateforme GRC – Gouvernance Risk Compliance",
            "Mise en place d'un outil GRC pour une banque : cartographie risques opérationnels, conformité Bâle III, gestion des contrôles internes, tableaux de bord CODIR.",
            ProjectStatus.COMPLETED, d7, cp2, "Amen Bank", null,
            "2024-01-01", "2025-06-30", "890000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.41"),
            "AMEN-2024-GRC-001", "TND", bd("1.00"), bd("89000"),  bd("780.00"), bd("39.00"));

        Project p15 = proj("ENT-PORTAL-2025",    "Portail B2B Fournisseurs & Appels d'Offres",
            "Portail web pour la gestion des appels d'offres, qualification fournisseurs, suivi commandes et e-facturation. Connecté à l'ERP SAP existant.",
            ProjectStatus.COMPLETED, d8, cp3, "Poulina Group Holding", null,
            "2024-03-01", "2025-02-28", "420000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.40"),
            "PGH-2024-B2B-001", "TND", bd("1.00"), bd("42000"),  bd("380.00"), bd("19.00"));

        Project p16 = proj("ENT-DCMIG-2024",     "Migration Datacenter vers Infrastructure Hybride",
            "Migration physique et logique de 280 serveurs sur 18 mois. Virtualisation VMware, interconnexion Azure, reprise après sinistre (RTO < 4h).",
            ProjectStatus.COMPLETED, d8, cp4, "STEG – Société Tunisienne d'Electricité et de Gaz", null,
            "2023-01-01", "2024-12-31", "1100000", BusinessModel.GROUPEMENT, EngagementType.REGIE,   bd("0.37"),
            "STEG-2023-DC-001", "EUR", bd("3.30"), bd("110000"), bd("950.00"), bd("47.50"));

        Project p17 = proj("ENT-RPA-2024",       "Automatisation des Processus Métier RPA",
            "Déploiement UiPath pour automatiser 45 processus métier répétitifs (traitement sinistres, rapprochements bancaires, onboarding client). ROI mesuré à 6 mois.",
            ProjectStatus.COMPLETED, d1, cp5, "STAR Assurances", null,
            "2024-05-01", "2025-04-30", "560000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.38"),
            "STAR-2024-RPA-001", "TND", bd("1.00"), bd("56000"),  bd("490.00"), bd("24.50"));

        Project p18 = proj("ENT-ARCH-2025",      "Numérisation des Archives Nationales",
            "Numérisation de 12 millions de documents d'archive, OCR avancé, indexation sémantique, portail de consultation citoyen, conservation préventive numérique.",
            ProjectStatus.COMPLETED, d2, cp6, "Archives Nationales de Tunisie", "UNESCO / Union Européenne",
            "2023-09-01", "2025-12-31", "2600000", BusinessModel.GROUPEMENT, EngagementType.FORFAIT, bd("0.32"),
            "ANT-2023-NUM-001", "EUR", bd("3.30"), bd("260000"), bd("2200.00"), bd("110.00"));

        // ── ON_HOLD (4) ───────────────────────────────────────────────────────
        // Suspended, not cancelled: contract still exists. Section 6 caps progress at 35% so
        // the KPI curve flattens where work stopped instead of just ending.
        Project p19 = proj("ENT-AI-2026",        "IA Prédictive – Maintenance Industrielle",
            "Plateforme d'intelligence artificielle pour la maintenance prédictive des équipements industriels. Modèles ML, capteurs IoT, tableau de bord temps réel.",
            ProjectStatus.ON_HOLD, d3, cp7, "Société Nationale des Chemins de Fer Tunisiens", "ONUDI",
            "2026-04-01", "2027-12-31", "1900000", BusinessModel.GROUPEMENT, EngagementType.REGIE,   bd("0.35"),
            "SNCFT-2026-AI-001", "EUR", bd("3.30"), bd("190000"), bd("1600.00"), bd("80.00"));

        Project p20 = proj("ENT-BLOCKCHAIN-2026","Traçabilité Blockchain – Industrie Pharmaceutique",
            "Solution blockchain (Hyperledger Fabric) pour la traçabilité et l'authenticité des médicaments. Intégration avec les systèmes douaniers et les laboratoires.",
            ProjectStatus.ON_HOLD, d4, cp8, "SIPHAT – Société de l'Industrie Pharmaceutique", null,
            "2026-07-01", "2027-06-30", "830000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.39"),
            "SIPHAT-2026-BC-001", "TND", bd("1.00"), bd("83000"),  bd("720.00"), bd("36.00"));

        Project p21 = proj("ENT-SMARTCITY-2026", "Smart City – Infrastructure Données Urbaines",
            "Plateforme Smart City pour la gestion intelligente du trafic, éclairage public connecté, collecte déchets optimisée, tableaux de bord municipaux.",
            ProjectStatus.ON_HOLD, d5, cp9, "Commune de Sfax", "Union Européenne / GIZ",
            "2026-09-01", "2029-08-31", "5000000", BusinessModel.GROUPEMENT, EngagementType.FORFAIT, bd("0.30"),
            "SFAX-2026-SC-001", "EUR", bd("3.30"), bd("500000"), bd("4200.00"), bd("210.00"));

        Project p22 = proj("ENT-PEDAG-2026",     "Plateforme Pédagogique En Ligne (E-Learning)",
            "LMS nouvelle génération pour l'enseignement supérieur : cours SCORM/xAPI, classes virtuelles, évaluation adaptative, analytics pédagogiques.",
            ProjectStatus.ON_HOLD, d6, cp10, "Université de Sfax", "BAD",
            "2026-08-01", "2027-07-31", "570000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.37"),
            "UDS-2026-LMS-001", "TND", bd("1.00"), bd("57000"),  bd("500.00"), bd("25.00"));

        // ── CANCELLED (3) ─────────────────────────────────────────────────────
        // Stopped for good; descriptions say why. Capped at 20% progress (section 6), no
        // mission/risk/stakeholder/DI, archived like COMPLETED.
        Project p23 = proj("ENT-LEGACY-2024",    "Migration Système Legacy COBOL vers Java",
            "Réécriture d'un système de gestion hérité en COBOL (années 80) vers Java/Spring. Arrêt suite à refonte de l'architecture par le client.",
            ProjectStatus.CANCELLED, d7, cp11, "Office National des Postes", null,
            "2023-10-01", "2025-09-30", "370000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.44"),
            "ONP-2023-LEG-001", "TND", bd("1.00"), bd("37000"),  bd("330.00"), bd("16.50"));

        Project p24 = proj("ENT-AUDIT-2024",     "Audit DSI & Maturité Numérique",
            "Audit complet du Système d'Information (infrastructure, applications, sécurité, gouvernance). Annulé après 3 mois pour raisons budgétaires.",
            ProjectStatus.CANCELLED, d7, cp12, "Société de Promotion du Lac Nord de Tunis", null,
            "2024-02-01", "2024-09-30", "150000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.48"),
            "SPLT-2024-AUD-001", "TND", bd("1.00"), bd("15000"),  bd("140.00"), bd("7.00"));

        Project p25 = proj("ENT-EXPER-2025",     "Expérimentation IA Générative – POC Chatbot",
            "POC d'un assistant IA générative pour le service client d'une compagnie d'assurance. Arrêt après POC : client a décidé une solution SaaS.",
            ProjectStatus.CANCELLED, d8, cp1, "Carte – Assurances", null,
            "2024-11-01", "2025-04-30", "280000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.45"),
            "CARTE-2024-AI-001", "TND", bd("1.00"), bd("28000"),  bd("260.00"), bd("13.00"));

        // ── DRAFT (5) ─────────────────────────────────────────────────────────
        // Not yet signed: client, funder, contractId, licence budget, sold/warranty workload are
        // all null on purpose (an unsigned project has none of these). Later sections skip
        // DRAFT rows via "if (status == DRAFT) continue;".
        Project p26 = proj("ENT-NLP-2027",       "NLP & Chatbot Multilingue Service Public",
            "Développement d'un assistant conversationnel arabe-français-anglais pour les services en ligne de l'État. Fine-tuning LLM sur corpus juridique tunisien.",
            ProjectStatus.DRAFT, d8, cp2, null, null,
            "2027-01-01", "2028-06-30", "660000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.40"),
            null, "TND", bd("1.00"), null, null, null);

        Project p27 = proj("ENT-OCR-2027",       "OCR Intelligent & Automatisation Documents Fiscaux",
            "Solution OCR/ICR avancée pour la numérisation automatique des déclarations fiscales. Intégration avec le SI de la DGI.",
            ProjectStatus.DRAFT, d1, cp3, null, null,
            "2027-03-01", "2028-02-28", "440000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.41"),
            null, "TND", bd("1.00"), null, null, null);

        Project p28 = proj("ENT-MICROSERV-2027", "Migration Architecture Microservices – Secteur Assurance",
            "Décomposition du monolithe applicatif d'une compagnie d'assurance en 35 microservices indépendants. Kubernetes, API Gateway, event sourcing.",
            ProjectStatus.DRAFT, d2, cp4, null, null,
            "2027-06-01", "2028-11-30", "1350000", BusinessModel.GROUPEMENT, EngagementType.FORFAIT, bd("0.36"),
            null, "EUR", bd("3.30"), null, null, null);

        Project p29 = proj("ENT-DATALAKE-2027",  "Data Lake & Plateforme Analytics Temps Réel",
            "Construction d'un Data Lake sur Databricks / Delta Lake pour consolider 15 sources de données hétérogènes. Dashboards Metabase temps réel.",
            ProjectStatus.DRAFT, d3, cp5, null, null,
            "2027-02-01", "2028-07-31", "2400000", BusinessModel.SEUL,      EngagementType.REGIE,   bd("0.33"),
            null, "EUR", bd("3.30"), null, null, null);

        Project p30 = proj("ENT-IDSNUM-2027",    "Identité Numérique Souveraine – PKI Nationale",
            "Infrastructure à Clé Publique nationale, signature électronique qualifiée, CNI numérique. Conforme eIDAS et réglementation ANSSi.",
            ProjectStatus.DRAFT, d4, cp6, null, null,
            "2027-07-01", "2029-12-31", "3100000", BusinessModel.SEUL,      EngagementType.FORFAIT, bd("0.30"),
            null, "EUR", bd("3.30"), null, null, null);

        // Index used by every later section: devIdx[i], projDays[i], overrunFactor[i] all
        // describe allProjs.get(i). Immutable so an accidental add/remove fails loudly instead
        // of silently shifting every index.
        List<Project> allProjs = List.of(
            p01, p02, p03, p04, p05, p06, p07, p08, p09, p10,
            p11, p12, p13, p14, p15, p16, p17, p18, p19, p20,
            p21, p22, p23, p24, p25, p26, p27, p28, p29, p30
        );

        // ── 4. Team assignments ───────────────────────────────────────────────
        // TeamAssignment links a project to a person with their job title on it. This matters
        // beyond decoration: per ADR-021, ProjectScopeInterceptor checks both permission AND
        // team membership on /api/projects/{id}/** - without these rows a seeded developer
        // would be refused on every project page despite holding the right permissions.
        //
        // devIdx[i] lists positions in devs[] working on allProjs.get(i); trailing // p01...
        // comments keep the 30 rows aligned with the 30 projects. Numbers within one row are
        // unique (uk_ta_project_user_active forbids duplicates); the same number recurs across
        // rows on purpose so a developer is over-booked across projects.
        int[][] devIdx = {
            {0,1,2,3,4},          // p01
            {5,6,7,8,9},          // p02
            {10,11,12,13},        // p03
            {0,4,14,15,16},       // p04
            {1,5,17,18},          // p05
            {2,6,19,20,21},       // p06
            {3,7,22,23},          // p07
            {8,12,24,25},         // p08
            {9,13,26,27},         // p09
            {10,14,28,29,0},      // p10
            {11,15,1,2,3},        // p11
            {16,17,4,5},          // p12
            {18,19,6,7,8},        // p13
            {20,21,9,10},         // p14
            {22,23,11,12},        // p15
            {24,25,13,14,15},     // p16
            {26,27,16,17},        // p17
            {28,29,18,19,20},     // p18
            {0,21,22,23},         // p19
            {1,24,25,26},         // p20
            {2,27,28,29},         // p21
            {3,4,5},              // p22
            {6,7},                // p23
            {8},                  // p24
            {9,10},               // p25
            {11,12},              // p26 (draft)
            {13,14},              // p27 (draft)
            {15,16,17},           // p28 (draft)
            {18,19},              // p29 (draft)
            {20,21},              // p30 (draft)
        };
        // Free-text label stored in team_assignments.role_in_team - NOT the RBAC role, grants
        // nothing (access is via hasAuthority on the Role entity's permissions).
        String[] roleLabels = {
            "Développeur Backend Senior", "Développeur Frontend", "Développeur Full-Stack",
            "Analyste Fonctionnel", "Architecte Solution", "Testeur QA Senior",
            "Consultant Fonctionnel", "Ingénieur DevOps", "Lead Developer", "Scrum Master"
        };

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj   = allProjs.get(i);
            User    chef  = prj.getChefProjet();
            // Added as his own team row (not just via project.chefProjet) so the ADR-021 scope
            // check does not refuse him on the project he runs. Null check is defensive: the
            // column is nullable even though all 30 projects above do set it.
            if (chef != null) {
                teamRepo.save(TeamAssignment.builder()
                    .project(prj).user(chef).roleInTeam("Chef de Projet")
                    // Copies project dates so workload screens (which filter by period) don't drop the row.
                    .startDate(prj.getStartDate()).endDate(prj.getEndDate())
                    .build());
            }
            int[] members = devIdx[i];
            for (int j = 0; j < members.length; j++) {
                // members[j] is a position in the devs array, not a database id.
                User dev = devs[members[j]];
                teamRepo.save(TeamAssignment.builder()
                    .project(prj).user(dev)
                    // Wraps the 10 titles so a team never runs out; teams here are at most 5, so titles stay distinct.
                    .roleInTeam(roleLabels[j % roleLabels.length])
                    .startDate(prj.getStartDate()).endDate(prj.getEndDate())
                    .build());
            }
        }

        // ── 5. PlanCharge + ChargeReelle ──────────────────────────────────────
        // The biggest section: produces most of the 13 000 rows.
        // PlanCharge = days PLANNED per person/project/month; ChargeReelle = days ACTUALLY
        // worked, same keys plus validator. Kept as two tables so the KPI screens can compare
        // planned vs. done.
        //
        // Base JH/month per project — drives profitability scenarios
        // JH = "jour-homme" (one person, one day). Bigger projects get bigger figures. Stays
        // well under the plan_charges CHECK (planned_days <= 31) even after the variance below.
        double[] projDays = {
            14.0, 18.0, 22.0, 16.0, 13.0, 20.0, 24.0, 17.0, 15.0, 26.0,
            19.0, 12.0, 15.0, 20.0, 13.0, 22.0, 16.0, 24.0, 18.0, 14.0,
            21.0, 15.0, 19.0, 11.0, 17.0, 14.0, 12.0, 18.0, 16.0, 20.0
        };
        // Variation multipliers applied per project type: drives profit/loss
        // For each project, how real consumption compares to plan (>1.00 over-runs, <1.00
        // under-runs). Reused in section 6 to bend consumed budget the same way, so the
        // timesheet screen and the margin screen tell the same story and over-run alerts exist.
        double[] overrunFactor = {
            1.15, 0.95, 1.22, 0.98, 0.91, 0.97, 1.28, 1.05, 0.93, 1.35,
            1.02, 0.88, 0.96, 1.18, 0.93, 1.08, 0.97, 1.24, 1.12, 1.00,
            1.06, 0.95, 1.20, 1.14, 1.08, 1.00, 1.00, 1.00, 1.00, 1.00
        };

        // "Today", read ONCE and reused by sections 5, 6 and 10: months before it get real
        // timesheets/KPI snapshots, months after get only a plan. Reading once avoids a run
        // straddling midnight producing an inconsistent "today". Dataset ages with real time.
        LocalDate refDate = LocalDate.now();

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // DRAFT hasn't started, CANCELLED was stopped: neither gets a timesheet.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;

            User[] members = new User[devIdx[i].length];
            for (int j = 0; j < devIdx[i].length; j++) members[j] = devs[devIdx[i][j]];
            if (members.length == 0) continue;

            double baseDays     = projDays[i];
            double overrun      = overrunFactor[i];
            // validated_by must be non-null; d1 fallback covers a manager-less project (none exist today).
            User   validator    = prj.getChefProjet() != null ? prj.getChefProjet() : d1;
            // Fallback dates guard against nullable start/end columns crashing the month-cursor walk.
            LocalDate start     = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate end       = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2026, 12, 31);
            // period always means "the 1st of the month" (uk_pc_active / uk_cr_active rely on it).
            LocalDate cursor    = start.withDayOfMonth(1);

            // Inclusive walk to the last month (a project ending 2027-03-31 must have a March 2027 row).
            while (!cursor.isAfter(end)) {
                // Small repeating wobble (+2.0/-1.5/+0.5/-0.5 by month%4) so the curve isn't flat; deterministic.
                double variance = (cursor.getMonthValue() % 4 == 0) ? 2.0
                                : (cursor.getMonthValue() % 4 == 1) ? -1.5
                                : (cursor.getMonthValue() % 4 == 2) ? 0.5 : -0.5;
                // Floor for the plan_charges CHECK (planned_days > 0 AND <= 31); not reached today,
                // guards a future lower projDays value.
                // Locale.US forces a decimal dot; a French/Tunisian default locale would produce
                // "14,5" and throw NumberFormatException in bd(...).
                BigDecimal planned = bd(String.format(Locale.US, "%.2f", Math.max(1.0, baseDays + variance)));

                for (User dev : members) {
                    // One planned row per member per month, including future months, to fill capacity screens.
                    planRepo.save(PlanCharge.builder()
                        .project(prj).user(dev).period(cursor).plannedDays(planned)
                        .build());

                    // Real days exist only for months already over.
                    if (cursor.isBefore(refDate)) {
                        // Real consumption = over-run factor times a 0.88-1.20 ripple. The %17 (prime)
                        // avoids a short visible cycle; mixing in project index i decorrelates projects.
                        double actualMult = overrun * (0.88 + (Math.abs((cursor.getMonthValue() * 7 + i * 3) % 17) * 0.02));
                        BigDecimal actual = planned.multiply(bd(String.format(Locale.US, "%.4f", actualMult)))
                            // actual_days is NUMERIC(5,2).
                            .setScale(2, RoundingMode.HALF_UP);
                        // Clamps for charges_reelles' CHECK (actual_days BETWEEN 0 AND 31), which the
                        // worst-case combination of factors could otherwise exceed.
                        if (actual.compareTo(bd("31.00")) > 0) actual = bd("31.00");
                        // compareTo (not equals) so 0.00 and 0.0 are treated as the same value.
                        if (actual.compareTo(BigDecimal.ZERO) <= 0) actual = bd("0.50");
                        chargeRepo.save(ChargeReelle.builder()
                            .project(prj).user(dev).period(cursor).actualDays(actual)
                            // Submit-then-validate trail so validated totals aren't all zero on screen.
                            .submittedAt(cursor.plusDays(22).atStartOfDay())
                            .validatedAt(cursor.plusDays(26).atStartOfDay())
                            .validatedBy(validator)
                            .build());
                    }
                }
                cursor = cursor.plusMonths(1);
            }
        }

        // ── 6. SnapshotKpi ────────────────────────────────────────────────────
        // One row per project per month: budget planned/consumed, EAC, margin, progress,
        // produced revenue, invoiced amount, a free-text highlight. Unlike the rest of PMS
        // (and the DI), these figures are STORED, not recomputed - a snapshot is history, and
        // recomputing today would use timesheets/budget that have since been corrected/amended.
        // Glossary: EV = earned value (% work produced), EAC = estimate at completion,
        // RAF = reste à faire (days left), CA de production = revenue matching work produced,
        // FAE = facture à établir (produced but not yet invoiced).
        // Free-text highlights, fictional, rotated over the 30 projects so the KPI comment column isn't empty.
        String[] faitsMarquants = {
            "Revue COPIL validée. Livraison phase 1 conforme aux attentes du client.",
            "Léger retard de 2 semaines sur le module d'intégration — plan d'action présenté.",
            "Sprint review réussi : 98 % des user stories livrées. Client très satisfait.",
            "Dérive de charge détectée (+12 %) — renégociation périmètre avec MOA en cours.",
            "Recette utilisateurs lancée. 87 % des cas de test passés dès le premier tour.",
            "Avenant signé pour extension de périmètre (+15 %). Budget révisé à la hausse.",
            "Go-live partiel effectué en production. Phase 2 planifiée pour le trimestre suivant.",
            "Audit qualité interne réalisé — 0 anomalie bloquante. Livraison finale confirmée.",
            "Retard fournisseur sur licences logicielles — escalade en cours avec le DG client.",
            "Formation des 85 utilisateurs clés réalisée en 3 semaines. Adoption excellente.",
        };

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // DRAFT: nothing produced yet, skipped. CANCELLED is NOT skipped: its history up
            // to the 20% ceiling below must stay visible.
            if (prj.getStatus() == ProjectStatus.DRAFT) continue;

            LocalDate start = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate end   = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2026, 12, 31);
            // Fallback only guards a null column; all 30 projects above have a budget.
            BigDecimal budget = prj.getInitialBudget() != null ? prj.getInitialBudget() : bd("500000");

            LocalDate cursor = start.withDayOfMonth(1);
            // How many snapshots of this project so far - drives the climbing curve.
            int snap = 0;
            // Progress ceiling per status: COMPLETED tops out at 100%, ON_HOLD flattens at 35%,
            // CANCELLED dies early at 20%, ACTIVE keeps climbing to 85% (still in progress).
            double maxSnapPct = switch (prj.getStatus()) {
                case COMPLETED -> 100.0;
                case ON_HOLD   -> 35.0;
                case CANCELLED -> 20.0;
                default        -> 85.0;
            };

            // Stay inside the project AND in the past - a snapshot is history, not a forecast.
            while (!cursor.isAfter(end) && cursor.isBefore(refDate)) {
                // Straight-line progress: 3.5% + 7.5pt/month, capped by the status ceiling.
                double rawPct  = Math.min(maxSnapPct, snap * 7.5 + 3.5);
                // Earned value wobbles around consumption (ahead every 3rd month) so the two aren't always equal.
                double evPct   = Math.min(100.0, rawPct + (snap % 3 == 0 ? 1.5 : -1.0));
                // Delivery runs 2pt ahead of earned value; both capped at 100 since they're percentages.
                double delPct  = Math.min(100.0, evPct + 2.0);
                // Same over-run factor as section 5, tying timesheets and money together.
                double factor  = overrunFactor[i];

                BigDecimal evPctBd  = bd(String.format(Locale.US, "%.2f", evPct));
                // Planned budget at this date = budget × progress.
                BigDecimal budPlan  = budget.multiply(bd(String.format(Locale.US, "%.4f", rawPct / 100.0))).setScale(2, RoundingMode.HALF_UP);
                // Consumed = planned bent by over-run factor.
                BigDecimal budConso = budPlan.multiply(bd(String.format(Locale.US, "%.4f", factor))).setScale(2, RoundingMode.HALF_UP);
                // Negative whenever factor > 1.00 - the dataset needs loss-making projects for the alert screens.
                BigDecimal margeK   = budPlan.subtract(budConso);
                // EAC: full budget bent by the same factor.
                BigDecimal eac      = budget.multiply(bd(String.format(Locale.US, "%.4f", factor))).setScale(2, RoundingMode.HALF_UP);
                // Produced revenue follows EARNED VALUE, not consumption - a company earns on what it produced.
                BigDecimal caProd   = budget.multiply(bd(String.format(Locale.US, "%.4f", evPct / 100.0))).setScale(2, RoundingMode.HALF_UP);
                // 65% of produced revenue invoiced; the rest becomes FAE below.
                BigDecimal factPct  = caProd.multiply(bd("0.65")).setScale(2, RoundingMode.HALF_UP);

                kpiRepo.save(SnapshotKpi.builder()
                    .project(prj).snapshotDate(cursor)
                    .budgetPlanifie(budPlan).budgetConsome(budConso).eac(eac).marge(margeK)
                    // Fraction (0.4250), matching the NUMERIC(*,4) column.
                    .tauxConsommation(bd(String.format(Locale.US, "%.4f", rawPct / 100.0)))
                    .evPct(evPctBd)
                    .deliveryPct(bd(String.format(Locale.US, "%.2f", delPct)))
                    // Days consumed/left, derived from progress with fixed coefficients - not meant to
                    // match section 5's timesheets row by row.
                    .consommeJh(bd(String.format(Locale.US, "%.2f", rawPct * 3.5)))
                    .rafJh(bd(String.format(Locale.US, "%.2f", (100.0 - rawPct) * 3.2)))
                    // Zero at factor 1.00, positive when over-burning, negative when under.
                    .deriveJh(bd(String.format(Locale.US, "%.2f", (factor - 1.0) * rawPct * 2.0)))
                    .caProduction(caProd).totalFacture(factPct)
                    // By subtraction, not "35% of caProd", so the three figures always add up exactly.
                    .fae(caProd.subtract(factPct))
                    .margeActuelle(margeK)
                    // "budPlan == 0 ? 1 : budPlan" avoids a silent NaN (double division by zero doesn't
                    // throw) that would otherwise become an unparsable "NaN" string below.
                    .margeActuellePct(bd(String.format(Locale.US, "%.4f", margeK.doubleValue() / (budPlan.doubleValue() == 0 ? 1 : budPlan.doubleValue()))))
                    // Forecast end date cycles on-time/+15 days late/-7 days early so the deviation column isn't empty.
                    .dateFinEstimee(end.plusDays(snap % 3 == 0 ? 0 : snap % 3 == 1 ? 15 : -7))
                    // (snap + i) offsets which highlight each project starts on, so neighbours don't repeat.
                    .faitsMarquants(faitsMarquants[(snap + i) % faitsMarquants.length])
                    .build());
                snap++;
                cursor = cursor.plusMonths(1);
            }
        }

        // ── 7. Missions + Composantes ─────────────────────────────────────────
        // Mission = one person travelling for one project (purpose, place, dates).
        // ComposanteMission = one cost line of that trip (transport, hotel, per diem, stamp
        // duty, ticket) - split out so expenses group by type and a trip can have 2-5 lines.
        //
        // Trip purposes, rotated over projects so no two show the same list.
        String[] missionObjets = {
            "Atelier de cadrage fonctionnel avec la MOA client",
            "Présentation du bilan de sprint et démo interactive",
            "Formation des administrateurs fonctionnels (niveau 2)",
            "Audit technique de l'infrastructure existante",
            "Réunion de validation des livrables — jalon contractuel",
            "Séminaire de lancement officiel du projet",
            "Workshop conduite du changement et communication",
            "Revue de sécurité et audit conformité réglementaire",
            "Session de test d'acceptation utilisateurs (UAT)",
            "Réunion de clôture et bilan REX",
            "Atelier d'analyse des écarts spécifications / réalisation",
            "Réunion de pilotage CODIR mensuelle",
            "Formation des développeurs aux bonnes pratiques sécurité",
            "Présentation intermédiaire bailleur de fonds",
            "Revue architecturale avec le comité technique du client",
        };
        // Real Tunisian towns, so a demonstration reads naturally to a local jury.
        String[] lieux = {"Tunis", "Sfax", "Sousse", "Monastir", "Nabeul", "Bizerte",
            "Ariana", "La Marsa", "Hammam-Lif", "Manouba", "Ben Arous", "Zaghouan"};

        // Separate filtered list (not a "continue") because the mission count "3 + (i % 4)"
        // needs i to count only the kept projects, not the position in allProjs.
        List<Project> missionProjs = new ArrayList<>();
        for (Project prj : allProjs) {
            if (prj.getStatus() != ProjectStatus.DRAFT && prj.getStatus() != ProjectStatus.CANCELLED)
                missionProjs.add(prj);
        }

        for (int i = 0; i < missionProjs.size(); i++) {
            Project prj = missionProjs.get(i);
            int nbMissions = 3 + (i % 4); // 3-6 missions per project
            // Maps back to the position in the full 30-project list, since devIdx is indexed on that list.
            int[] members  = devIdx[allProjs.indexOf(prj)];
            User[] mUsers  = new User[members.length];
            for (int k = 0; k < members.length; k++) mUsers[k] = devs[members[k]];
            if (mUsers.length == 0) continue;

            LocalDate pStart = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 6, 1);

            for (int m = 0; m < nbMissions; m++) {
                // One month after project start, then monthly, so trips stay inside the project window.
                LocalDate mStart = pStart.plusMonths(m + 1L);
                // Trip length cycles 1-4 days; varying it keeps the hotel-nights description below from repeating.
                LocalDate mEnd   = mStart.plusDays(1 + m % 4);
                // Round-robin across the team so several members travel before anyone repeats.
                User mUser = mUsers[m % mUsers.length];

                // Saved first: the expense lines below need its generated id as FK.
                Mission mission = missionRepo.save(Mission.builder()
                    .project(prj).user(mUser)
                    // (i + m) so project 2's first trip doesn't match project 1's.
                    .objet(missionObjets[(i + m) % missionObjets.length])
                    .lieu(lieux[(i + m) % lieux.length])
                    .dateDebut(mStart).dateFin(mEnd)
                    .build());

                // Transport + Hébergement always exist - every trip needs getting there and sleeping somewhere.
                composanteRepo.save(ComposanteMission.builder().mission(mission)
                    .typeComposante(TypeComposante.TRANSPORT)
                    .montant(bd(String.valueOf(60 + (m * 20) + (i * 5))))
                    // Expenses are always TND, paid locally regardless of contract currency.
                    .devise("TND").description("Transport Tunis — " + lieux[(i + m) % lieux.length] + " A/R")
                    .build());
                composanteRepo.save(ComposanteMission.builder().mission(mission)
                    .typeComposante(TypeComposante.SEJOUR)
                    .montant(bd(String.valueOf(110 + m * 15)))
                    // Nights recomputed from the two dates so the text can't contradict them. Only
                    // correct within one calendar year, which holds for every trip here.
                    .devise("TND").description("Hébergement hôtel " + (mEnd.getDayOfYear() - mStart.getDayOfYear() + 1) + " nuit(s)")
                    .build());
                // Every second mission also gets a meal allowance - uneven lines make totals vary trip to trip.
                if (m % 2 == 0) {
                    composanteRepo.save(ComposanteMission.builder().mission(mission)
                        .typeComposante(TypeComposante.PERDIEM)
                        .montant(bd(String.valueOf(40 + m * 8)))
                        .devise("TND").description("Per diem repas déjeuner/dîner client")
                        .build());
                }
                // Every third one a stamp duty, fixed at 8 TND (a legal fixed fee).
                if (m % 3 == 0) {
                    composanteRepo.save(ComposanteMission.builder().mission(mission)
                        .typeComposante(TypeComposante.TIMBRE)
                        .montant(bd("8"))
                        .devise("TND").description("Timbre fiscal bon de commande mission")
                        .build());
                }
                // Plane ticket only on the first mission of every fourth project - rare, to exercise
                // the expense screen's handling of one large outlier line.
                if (i % 4 == 0 && m == 0) {
                    composanteRepo.save(ComposanteMission.builder().mission(mission)
                        .typeComposante(TypeComposante.BILLET)
                        .montant(bd(String.valueOf(450 + i * 30)))
                        .devise("TND").description("Billet avion Tunis–Paris mission internationale")
                        .build());
                }
            }
        }

        // ── 8. Avenants + JalonFacturation + Paiements ────────────────────────
        // JalonFacturation = invoicing milestone (PREVU -> FACTURE -> PAYE); Paiement = a
        // payment against one; Avenant = a contract amendment (scope/money, up or down).
        //
        // Four standard payment-schedule templates (7/6/5/5 milestones), each with a matching
        // percentage array read at the same index - they must stay in sync.
        String[][] jalonTemplates = {
            {"Démarrage & Installation", "Livraison Lot 1 – Analyse & Conception",
             "Livraison Lot 2 – Développement", "Livraison Lot 3 – Intégration",
             "Recette utilisateurs", "Mise en production", "Clôture & Garantie"},
            {"Démarrage / Mobilisation équipe", "Jalons mi-phase 1",
             "Livraison phase 1", "Livraison phase 2", "Réception provisoire", "Réception définitive"},
            {"Lancement", "Milestone Alpha", "Milestone Beta",
             "Recette finale", "Go-Live"},
            {"Démarrage", "Revue intermédiaire", "Livraison technique",
             "Recette", "Clôture"},
        };
        // Each row sums to exactly 100.0 - the milestones together ARE the contract - and has
        // the same length as its label row above.
        double[][] jalonPctTemplates = {
            {8.0, 12.0, 20.0, 20.0, 15.0, 15.0, 10.0},
            {10.0, 10.0, 20.0, 25.0, 20.0, 15.0},
            {10.0, 20.0, 25.0, 25.0, 20.0},
            {15.0, 20.0, 30.0, 20.0, 15.0},
        };

        // Fixed date, not LocalDate.now(): invoicing is accounting, so the split between
        // already-invoiced and still-planned milestones must not shift between demo runs.
        // Ages after 1 January 2026 (dataset stops producing new invoices by itself).
        LocalDate billingCutoff = LocalDate.of(2026, 1, 1);

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // Only DRAFT skipped; CANCELLED still gets milestones for work already delivered before the stop.
            if (prj.getStatus() == ProjectStatus.DRAFT) continue;

            BigDecimal budget = prj.getInitialBudget() != null ? prj.getInitialBudget() : bd("500000");
            LocalDate pStart  = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate pEnd    = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2026, 12, 31);
            // Full package name since ChronoUnit is used only here and not imported at the top.
            long dur          = java.time.temporal.ChronoUnit.DAYS.between(pStart, pEnd);

            // Cycle through the four schedules across the 30 projects.
            int tmpl          = i % jalonTemplates.length;
            String[] labels   = jalonTemplates[tmpl];
            double[] pcts     = jalonPctTemplates[tmpl];

            for (int j = 0; j < labels.length; j++) {
                // (j + 1), not j, so the first milestone isn't on the start date and the last lands
                // exactly on the end date. "(double)" cast avoids integer division (1/7 == 0).
                double ratio     = (double)(j + 1) / labels.length;
                LocalDate datePrev = pStart.plusDays((long)(dur * ratio));
                // Due before the accounting cutoff means already invoiced.
                boolean   past   = datePrev.isBefore(billingCutoff);
                // Every 4th past milestone PAYE, others FACTURE, future ones PREVU - the cash-collection
                // screen needs all three states.
                JalonStatut statut = past ? (j % 4 == 3 ? JalonStatut.PAYE : JalonStatut.FACTURE) : JalonStatut.PREVU;
                // Invoice 5 days after the milestone; null (meaningful, not missing) while still in the future.
                LocalDate dateFact = past ? datePrev.plusDays(5) : null;
                // Safety net in case a template's percentage row ever falls out of sync with its labels.
                double    pctVal   = pcts[j % pcts.length];
                // Stored alongside the percentage so a later amendment to the budget can't move an issued invoice.
                BigDecimal montant = budget.multiply(bd(String.format(Locale.US, "%.4f", pctVal / 100.0))).setScale(2, RoundingMode.HALF_UP);

                // Saved first: the payment below needs the milestone id as FK.
                JalonFacturation jalon = jalonRepo.save(JalonFacturation.builder()
                    .project(prj).label(labels[j])
                    .pourcentage(bd(String.format(Locale.US, "%.2f", pctVal)))
                    .montant(montant).datePrevue(datePrev)
                    .dateFacture(dateFact).statut(statut)
                    .build());

                // "montant != null" is always true (dead condition, left as-is: comments-only pass).
                // Known inconsistency: payment is written for the full amount even when only FACTURE,
                // not PAYE, unlike JalonService.recalculerStatut() in the live application.
                if ((statut == JalonStatut.FACTURE || statut == JalonStatut.PAYE) && montant != null) {
                    paiementRepo.save(Paiement.builder()
                        .jalon(jalon).montantRecu(montant)
                        // 12-day payment delay; fallback branch is unreachable but keeps the expression total.
                        .datePaiement(dateFact != null ? dateFact.plusDays(12) : pStart.plusDays(30))
                        // Readable, unique transfer reference so the accounting screen's search has something to find.
                        .reference("VIR-" + prj.getCode().replace("ENT-", "") + "-J" + (j + 1) + "-" + (dateFact != null ? dateFact.getYear() : 2025))
                        .build());
                }
            }

            // Up to three amendments per live/finished project, from three independent "i % n"
            // tests so projects end up with 0-3 amendments and the amendment tab shows both
            // empty and full cases.
            if (prj.getStatus() == ProjectStatus.ACTIVE || prj.getStatus() == ProjectStatus.COMPLETED) {
                // Every third project: extra scope, +10 % of budget and 40 extra days.
                if (i % 3 == 0) {
                    avenantRepo.save(Avenant.builder()
                        .project(prj).numero("AV-ENT-001")
                        .objet("Extension de périmètre — module complémentaire demandé par le CODIR client")
                        .montant(budget.multiply(bd("0.10")).setScale(2, RoundingMode.HALF_UP))
                        .workloadDays(bd("40.00"))
                        // Dated 4 months in: an amendment is negotiated once the project has
                        // started and the client has seen the first deliveries.
                        .dateAvenant(pStart.plusMonths(4))
                        .build());
                }
                // Every fifth project: a corrective amendment after acceptance, +6 %.
                if (i % 5 == 0) {
                    avenantRepo.save(Avenant.builder()
                        .project(prj).numero("AV-ENT-002")
                        .objet("Avenant correctif post-recette — prise en charge anomalies critiques phase 1")
                        .montant(budget.multiply(bd("0.06")).setScale(2, RoundingMode.HALF_UP))
                        .workloadDays(bd("22.00"))
                        .dateAvenant(pStart.plusMonths(7))
                        .build());
                }
                // Every seventh non-TND project: a NEGATIVE amendment (-4% budget, -10 days) so
                // the dataset also exercises a shrinking contract. "TND".equals(currency), not
                // currency.equals("TND"), so a null currency doesn't throw.
                if (i % 7 == 0 && !"TND".equals(prj.getCurrency())) {
                    avenantRepo.save(Avenant.builder()
                        .project(prj).numero("AV-ENT-003")
                        .objet("Révision à la baisse suite au taux de change favorable — réduction budgétaire")
                        .montant(budget.multiply(bd("-0.04")).setScale(2, RoundingMode.HALF_UP))
                        .workloadDays(bd("-10.00"))
                        .dateAvenant(pStart.plusMonths(10))
                        .build());
                }
            }
        }

        // ── 9. Risks ──────────────────────────────────────────────────────────
        // Risk: description, probabilite/impact (NiveauRisque: FAIBLE/MOYEN/ELEVE), planMitigation,
        // statut - the three-level scale is what lets the screen draw the probability×impact matrix.
        //
        // Eighteen recurring project risks, paired one-for-one with the mitigations array below
        // (index 0 answers index 0) - though see the note further down, the loop reads them with
        // different strides so that pairing does not survive into the seeded rows.
        String[] riskDescs = {
            "Retard de validation des spécifications fonctionnelles par la MOA — impact planning phase 2.",
            "Dépassement budgétaire potentiel lié à la complexité d'intégration des systèmes existants.",
            "Indisponibilité de ressources clés (architecte, DBA) lors de la phase critique de développement.",
            "Résistance au changement des utilisateurs finaux — risque d'adoption insuffisante post-déploiement.",
            "Dépendance vis-à-vis d'un fournisseur tiers unique pour les licences logicielles critiques.",
            "Instabilité de l'environnement de recette client — tests bloqués plusieurs fois.",
            "Évolutions réglementaires imprévues pouvant nécessiter une refonte partielle des modules conformité.",
            "Problèmes de performance sur la base de données legacy lors des pics de charge.",
            "Risque de sécurité lié à l'exposition des API REST sur des réseaux non sécurisés.",
            "Non-disponibilité des données de migration dans les délais contractuels prévus.",
            "Incompatibilité de versions entre composants tiers découverte en phase d'intégration.",
            "Manque de compétences internes du client sur la technologie cible — formation insuffisante.",
            "Risque de dépassement de la provision pour pénalités de retard (PPP) si jalons dépassés.",
            "Fluctuation du taux de change EUR/TND impactant le budget effectif en TND.",
            "Turn-over d'un développeur senior en phase critique — perte de compétences sur le projet.",
            "Défaillance du fournisseur d'hébergement cloud — risque de continuité de service.",
            "Conflit de priorités entre ce projet et un autre projet stratégique chez le même client.",
            "Qualité insuffisante des livrables du sous-traitant — risque de reprise des développements.",
        };
        // The eighteen answers, index-aligned with riskDescs above.
        String[] mitigations = {
            "Mise en place d'un comité de validation hebdomadaire avec représentants MOA et MOE.",
            "Révision du planning de charge avec buffer de 15 % — escalade au CODIR si dépassement 10 %.",
            "Identification d'une ressource de secours qualifiée. Transfert de connaissance systématique.",
            "Plan de conduite du changement renforcé : ateliers co-construction, champions métier identifiés.",
            "Diversification des fournisseurs — clause de sortie négociée dans le contrat de licence.",
            "Mise en place d'un environnement miroir géré par ST2I pour les tests critiques.",
            "Veille réglementaire mensuelle. Clause contractuelle de révision de périmètre si loi modifiée.",
            "Migration progressive vers la nouvelle base. Tests de performance systématiques chaque sprint.",
            "Mise en place d'un WAF, audit de sécurité API avant chaque déploiement en production.",
            "Atelier de préparation des données avec le client J-60 avant la phase de migration.",
            "Matrice de compatibilité maintenue à jour. Tests d'intégration continue (CI/CD).",
            "Programme de formation accélérée — 3 jours d'atelier techniques pour les équipes client.",
            "Suivi mensuel PPP vs. avancement. Alerte automatique si dérive > 5 %.",
            "Clause de révision de taux dans le contrat. Reporting mensuel de l'impact change au CODIR.",
            "Plan de fidélisation : prime de rétention + plan de carrière. Documentation systématique.",
            "Multi-cloud strategy. SLA renforcé avec pénalités. Plan de reprise activité testé trimestriellement.",
            "Arbitrage au niveau Direction. Planning de ressources partagé entre les deux projets.",
            "Clause qualité dans le contrat sous-traitant. Revue hebdomadaire des livrables en cours.",
        };
        // Called once and reused (values() allocates a fresh array each call).
        // niveaux  = FAIBLE, MOYEN, ELEVE (3 values); statuts = OUVERT, MITIGE, FERME (3 values)
        NiveauRisque[] niveaux = NiveauRisque.values();
        StatutRisque[]  statuts = StatutRisque.values();

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // No risk register for a draft (not started) or a cancelled project (stopped).
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            int nbRisks = 3 + (i % 5); // 3-7 risks per project
            for (int r = 0; r < nbRisks; r++) {
                riskRepo.save(Risk.builder()
                    .project(prj)
                    // Stride 3 through the descriptions (wraps at 18, so a 7-risk project repeats its first).
                    .description(riskDescs[(i + r * 3) % riskDescs.length])
                    // One position apart in the same array so probabilite/impact are never equal
                    // on one row (avoids piling every risk on the matrix diagonal).
                    .probabilite(niveaux[(i + r)     % niveaux.length])
                    .impact(     niveaux[(i + r + 1) % niveaux.length])
                    // KNOWN DEFECT: stride 2 here vs. stride 3 above means only r=0's mitigation
                    // actually answers its description; from r=1 on the pairing drifts and can be
                    // meaningless. Fixing it means reading both arrays with the same index.
                    .planMitigation(mitigations[(i + r * 2) % mitigations.length])
                    .statut(statuts[(i + r) % statuts.length])
                    .build());
            }
        }

        // ── 10. Livrables ─────────────────────────────────────────────────────
        // Contractual deliverable: title, due date, status (EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE).
        // Its own table (not project prose) so due date and status can be queried directly.
        //
        // Twenty deliverable names. Abbreviations: CdCF = functional requirements, DAT =
        // technical architecture file, STI = integration specs, JTR = acceptance test set,
        // PVRT/PVRD = technical/final acceptance minutes, NMP = go-live note, REX = lessons learned.
        String[] livTitles = {
            "Cahier des charges fonctionnel validé (CdCF v1.0)",
            "Dossier d'architecture technique (DAT v2.0)",
            "Maquettes IHM validées par le client",
            "Prototype fonctionnel — démonstration Sprint 3",
            "Rapport d'audit de l'infrastructure existante",
            "Spécifications techniques d'intégration (STI)",
            "Rapport de tests de charge et performance",
            "Jeu de tests de recette (JTR) — 650 cas de test",
            "Manuel utilisateur et guide d'administration",
            "Procès-verbal de recette technique (PVRT)",
            "Procès-verbal de réception définitive (PVRD)",
            "Plan de formation et supports pédagogiques",
            "Rapport de migration des données (3 lots)",
            "Note de mise en production (NMP v1.0)",
            "Rapport REX et retour d'expérience final",
            "Tableau de bord de pilotage (v2.5)",
            "Rapport de conformité sécurité (RSSI)",
            "Documentation technique API (Swagger / OpenAPI 3.0)",
            "Rapport de qualification des tests UAT",
            "Schéma d'architecture cloud cible",
        };
        // Never used: status is decided by the if/else chain below instead. Dead, left as-is (comments-only pass).
        StatutLivrable[] livStatuts = StatutLivrable.values();

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // Cancelled projects keep their deliverables (work already produced); only drafts are skipped.
            if (prj.getStatus() == ProjectStatus.DRAFT) continue;
            int nbLiv = 4 + (i % 6); // 4-9 livrables per project
            LocalDate pStart = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);

            for (int l = 0; l < nbLiv; l++) {
                // One deliverable per month from the second month of the project.
                LocalDate echeance = pStart.plusMonths(l + 1L);
                StatutLivrable sl;
                // Status ladder, first matching branch wins, top to bottom.
                if (prj.getStatus() == ProjectStatus.COMPLETED) {
                    // Finished: all accepted except the last two (final acceptance lags the work itself).
                    sl = l < nbLiv - 2 ? StatutLivrable.VALIDE : StatutLivrable.LIVRE;
                } else if (echeance.isBefore(refDate.minusMonths(4))) {
                    // Due 4+ months ago: mostly settled, acceptance lagging delivery.
                    sl = l % 3 == 0 ? StatutLivrable.VALIDE : StatutLivrable.LIVRE;
                } else if (echeance.isBefore(refDate)) {
                    // Due recently: produces the LATE (EN_COURS past due) rows the alert screen needs.
                    sl = l % 2 == 0 ? StatutLivrable.LIVRE : StatutLivrable.EN_COURS;
                } else {
                    // Future: mostly waiting, some already started.
                    sl = l % 3 == 0 ? StatutLivrable.EN_COURS : StatutLivrable.EN_ATTENTE;
                }

                livrableRepo.save(Livrable.builder()
                    .project(prj)
                    // (i + l) so project 5 doesn't start its title list where project 4 did.
                    .titre(livTitles[(i + l) % livTitles.length])
                    // Built from the title so description can't contradict it; "Équipe projet"
                    // fallback when there's no manager (short-circuit avoids NPE).
                    .description("Livrable contractuel — " + livTitles[(i + l) % livTitles.length].toLowerCase()
                        + ". Responsable : " + (prj.getChefProjet() != null ? prj.getChefProjet().getFirstName() : "Équipe projet") + ".")
                    .dateEcheance(echeance)
                    .statut(sl)
                    .build());
            }
        }

        // ── 11. Parties Prenantes ─────────────────────────────────────────────
        // Client-side stakeholder register: sponsor, IT director, functional owner, funder's rep.
        // influence/interet use the same three-level scale as Risk, telling a PM whom to inform first.
        //
        // Five groups of five names; a project uses the first few names of one group so its
        // stakeholders read like one organisation rather than a random mix.
        String[][] ppPersonnes = {
            {"Rachid Hamdi","Sihem Ben Amor","Mourad Chatti","Houda Rouissi","Taher Nasri"},
            {"Nadia Bouhlel","Anis Mabrouk","Leila Chaabane","Youssef Trabelsi","Imen Ben Salah"},
            {"Habib Karray","Faiza Khemiri","Sami Jelassi","Roua Baccari","Walid Mansouri"},
            {"Dalila Ferchichi","Kamel Dridi","Sana Ouerghi","Mehdi Riahi","Olfa Zouari"},
            {"Slim Ben Fredj","Nour Gueddiche","Hajer Azouzi","Tarek Saadaoui","Rim Meddeb"},
        };
        // Also the raw material for the generated e-mail below; several end with a bracketed
        // abbreviation, which matters for the regex note further down.
        String[] fonctions = {
            "Directeur Général / Sponsor Exécutif",
            "Directeur des Systèmes d'Information (DSI)",
            "Responsable Fonctionnel MOA / Chef de Projet Client",
            "Représentant du Bailleur de Fonds",
            "Responsable des Utilisateurs Clés (RUC)",
            "Directeur Administratif et Financier (DAF)",
            "Responsable Sécurité des Systèmes d'Information (RSSI)",
            "Directeur Technique Client (DTC)",
        };
        // Same three-level scale as Risk, reused so both screens sort/colour levels identically.
        NiveauRisque[] ppNiveaux = NiveauRisque.values();

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // Draft has no client yet; cancelled has no stakeholders left to manage.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            int nbPP    = 2 + (i % 5); // 2-6 parties prenantes
            // "ENT-CRM-2026" -> "crm2026", used below as "...@crm2026.tn". Hyphens stripped so
            // the address can't start/end with one.
            String code = prj.getCode().toLowerCase().replace("ent-", "").replace("-", "");
            String[] noms = ppPersonnes[i % ppPersonnes.length];

            // Math.min guards nbPP (up to 6) against a group of only 5 names.
            for (int p = 0; p < Math.min(nbPP, noms.length); p++) {
                partieRepo.save(PartiePrenante.builder()
                    .project(prj)
                    .nom(noms[p])
                    .fonction(fonctions[(i + p) % fonctions.length])
                    // Job title lower-cased, every non a-z char (space, slash, brackets, accents)
                    // turned into a dot, then runs of dots collapsed to one.
                    // KNOWN DEFECT: 5 of the 8 titles end with a closing bracket - e.g. "(DSI)" -
                    // which becomes a trailing dot right before "@", an invalid address shape.
                    // No format check on the column, so the row is stored anyway.
                    .email(fonctions[(i + p) % fonctions.length].toLowerCase()
                        .replaceAll("[^a-z]", ".").replaceAll("\\.{2,}", ".")
                        + "@" + code + ".tn")
                    // Tunisian-looking "+216 7x nnn nnn"; %03d pads for column alignment.
                    .telephone("+216 7" + (i % 5) + " " + String.format("%03d", (p + 1) * 100 + i) + " " + String.format("%03d", (i + p) * 17 % 1000))
                    // One position apart, same array, so influence/interet are never equal on one row.
                    .influence(ppNiveaux[(i + p)     % ppNiveaux.length])
                    .interet(  ppNiveaux[(i + p + 1) % ppNiveaux.length])
                    .build());
            }
        }

        // ── 12. Demandes de Changement ────────────────────────────────────────
        // DemandeChangement: who asked, what for, urgency, decision (EN_ATTENTE -> APPROUVE/REJETE).
        // Distinct from an Avenant (section 8): this is the DISCUSSION, an amendment is the
        // SIGNED result of one - many requests are refused and never become an amendment.
        String[] dcTitres = {
            "Ajout d'un module de reporting décisionnel avancé (BI)",
            "Révision du workflow de validation multi-niveaux",
            "Extension de périmètre — intégration SSO avec l'AD client",
            "Ajout d'un tableau de bord temps réel pour le CODIR",
            "Migration base de données vers PostgreSQL 16.x",
            "Ajout de notifications push sur application mobile",
            "Refonte du module d'import/export de données (CSV, Excel, XML)",
            "Amélioration des performances — cache Redis distribué",
            "Intégration API gouvernementale (e-MIRSAID / e-COMECE)",
            "Ajout d'un module de gestion des habilitations (IAM)",
            "Personnalisation de l'interface selon la charte graphique client",
            "Support multilingue arabe (RTL) pour les interfaces web",
            "Mise en place d'une API REST pour les partenaires tiers",
            "Évolution du module reporting — formats additionnels (PDF, XLSX)",
            "Intégration avec le module comptable existant (SAP)",
        };
        // prios     = FAIBLE, NORMALE, ELEVEE, CRITIQUE (4 values)
        // scStatuts = EN_ATTENTE, APPROUVE, REJETE (3 values)
        // Lengths 4 and 3 share no common factor, so the (priority, status) pair only repeats
        // after 12 steps - every combination appears, which is what makes the screen's filters worth trying.
        PrioriteChangement[] prios = PrioriteChangement.values();
        StatutChangement[]   scStatuts = StatutChangement.values();

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // Nothing to change on a project that has not started or has been stopped.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            int nbDC  = 2 + (i % 4); // 2-5 demandes per project
            // d1 fallback for a manager-less project (none of the 30 above actually is one).
            User dem  = prj.getChefProjet() != null ? prj.getChefProjet() : d1;
            LocalDate pStart = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);

            for (int dc = 0; dc < nbDC; dc++) {
                PrioriteChangement  prio = prios[  (i + dc)     % prios.length];
                StatutChangement    sc   = scStatuts[(i + dc)   % scStatuts.length];
                // 2 months in, then every 3 - nobody requests scope change before work begins.
                LocalDate dateDemande = pStart.plusMonths(2L + dc * 3L);
                // Null (meaningful) while still EN_ATTENTE - a screen must not show a pending request as decided.
                LocalDate dateDecision = sc != StatutChangement.EN_ATTENTE ? dateDemande.plusDays(7 + dc * 3) : null;

                demandeRepo.save(DemandeChangement.builder()
                    .project(prj).demandeur(dem)
                    .titre(dcTitres[(i + dc) % dcTitres.length])
                    // Plain text, figures grow with request number for a readable screen (not computed elsewhere).
                    .description("Demande de changement #" + (dc + 1) + " initiée suite à la revue CODIR — "
                        + "impact estimé : " + (dc + 1) * 20 + " JH supplémentaires, "
                        + "délai : " + (dc + 1) * 2 + " semaines.")
                    .priorite(prio).statut(sc)
                    .dateDemande(dateDemande).dateDecision(dateDecision)
                    .build());
            }
        }

        // ── 13. LigneDi — STRUCTURE UNIQUEMENT (pas de valeurs financières) ──
        // SECURITY CONSTRAINT (AMENDED DECISION 2026-07-05): only section, ordre,
        // and profil_contractuel are populated. All monetary columns stay NULL.
        //
        // The DI (Devis Interne) is the company's internal cost/margin sheet, in three parts:
        // HONORAIRES (fee lines per contractual profile), FRAIS (direct expenses), AUTRES_FRAIS
        // (provisions and taxes). This seeder writes only the SHAPE - section, ordre, profile/
        // expense name, unit - and no money at all; those columns stay NULL per the amended
        // decision of 2026-07-05 (V23__devis_interne.sql, spec F-AFF-13): the DI ships as an
        // empty template, each deployment types in its own figures. DI totals are computed on
        // read, never stored, so an empty template is genuinely empty.
        //
        // Ten fee lines in sheet order (director down to security expert) - "ordre" records this.
        String[] diHonoraires = {
            "Chef de Projet Senior / Directeur de Projet",
            "Architecte Solution & Expert Technique",
            "Lead Developer / Développeur Senior Backend",
            "Développeur Senior Frontend / Mobile",
            "Développeur Full-Stack",
            "Analyste Fonctionnel Senior",
            "Testeur QA / AMOA",
            "Consultant Fonctionnel Métier",
            "Ingénieur DevOps / SRE",
            "Expert Sécurité / Auditeur",
        };
        // Five direct-expense lines, matching section 7's mission expense types so actual costs are comparable to the quote.
        String[] diFrais = {
            "Frais de déplacement mission terrain (TND/mission)",
            "Per diem équipe projet (repas + transport local)",
            "Billet avion mission internationale",
            "Frais hébergement déplacement longue durée",
            "Location salle de formation / séminaire",
        };
        // Five provisions/taxes, quoted as a % of amount sold rather than in days: PPR = risk
        // provision, RS = withholding tax, PPP = late-delivery penalty provision.
        // The "5%" inside these labels is TEXT only; the real rate column (taux_pourcentage) stays NULL.
        String[] diAutresFrais = {
            "Provision Pour Risques (PPR – 5 % budget vendu TND)",
            "Droits d'enregistrement contrat (timbre + enregistrement)",
            "Frais bancaires, cautions et garanties de bonne fin",
            "Retenue à la source IS / impôts directs (RS 5 %)",
            "Provision pour pénalités de retard (PPP)",
        };

        // ACTIVE and COMPLETED only (18 of 30): a DRAFT was never priced, a CANCELLED one was abandoned.
        List<Project> diProjs = new ArrayList<>();
        for (Project prj : allProjs) {
            if (prj.getStatus() == ProjectStatus.ACTIVE || prj.getStatus() == ProjectStatus.COMPLETED)
                diProjs.add(prj);
        }

        // 18 projects x (10 + 5 + 5) lines = 360 rows, all of them with empty amounts.
        for (Project prj : diProjs) {
            // HONORAIRES section
            for (int l = 0; l < diHonoraires.length; l++) {
                ligneDiRepo.save(LigneDi.builder()
                    // "ordre" restarts at 1 per section since the sheet/screen sort within each section.
                    .project(prj).section(SectionDi.HONORAIRES).ordre(l + 1)
                    .profilContractuel(diHonoraires[l])
                    // Fees are sold by the day: "H-Jour" is one person for one day.
                    .unite("H-Jour")
                    // Every financial field intentionally left null (décision 2026-07-05); all nullable in V23.
                    .build());
            }
            // FRAIS section
            for (int l = 0; l < diFrais.length; l++) {
                ligneDiRepo.save(LigneDi.builder()
                    .project(prj).section(SectionDi.FRAIS).ordre(l + 1)
                    .profilContractuel(diFrais[l])
                    // Expenses are a lump sum, not per day.
                    .unite("Forfait")
                    .build());
            }
            // AUTRES_FRAIS section
            for (int l = 0; l < diAutresFrais.length; l++) {
                ligneDiRepo.save(LigneDi.builder()
                    .project(prj).section(SectionDi.AUTRES_FRAIS).ordre(l + 1)
                    .profilContractuel(diAutresFrais[l])
                    // Percentage of total sold; the rate itself (taux_pourcentage) stays null.
                    .unite("%")
                    .build());
            }
        }

        // Only proof on the console that seeding finished; reaching this line means every section completed.
        log.info("EnterpriseDataSeeder — terminé : {} utilisateurs, {} projets, ressources + TCC + workload + KPIs + missions + facturation + gouvernance + DI.",
            allEntUsers.size(), allProjs.size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    // Small private methods that hide the noise of run(); private since nothing outside this class calls them.

    /** Finds one RBAC role by name, or null if it doesn't exist (run() logs one combined warning rather than throwing). */
    private Role roleByName(String name) {
        return roleRepo.findByName(name).orElse(null);
    }

    /**
     * Returns the user with this e-mail, creating it if missing, with its generated id.
     * Find-first-then-create avoids violating uk_users_email if run twice; orElseGet only
     * builds/saves when actually missing. active(true) + firstLogin(false) so demo logins work immediately.
     */
    private User user(String first, String last, String email, String pwd, Role role) {
        return userRepo.findActiveByEmailWithRole(email).orElseGet(() ->
            userRepo.save(User.builder()
                .firstName(first).lastName(last).email(email)
                .passwordHash(pwd).active(true).firstLogin(false)
                .role(role).build()));
    }

    /**
     * Builds and saves one Project from twenty positional arguments; folds section 3's thirty
     * builder chains into aligned one-line calls so a wrong value is caught by eye. The
     * positional-argument risk (values silently swapped) is accepted only because this is a
     * private, single-caller, column-aligned method - not acceptable on real user input.
     * Dates/budget arrive as strings so LocalDate.parse/new BigDecimal(String) fail loudly on
     * a typo instead of storing a wrong value or a double-rounding error.
     *
     * @return the saved Project - callers must use the returned object, which carries the
     *         generated id used as FK by team rows, timesheets, milestones and DI lines.
     */
    private Project proj(String code, String name, String desc,
                         ProjectStatus status, User director, User chef,
                         String client, String funder,
                         String start, String end, String budget,
                         BusinessModel bm, EngagementType et, BigDecimal marge,
                         String contractId, String currency, BigDecimal xRate,
                         BigDecimal licenseBudget, BigDecimal soldWl, BigDecimal warrantyWl) {
        return projectRepo.save(Project.builder()
            .code(code).name(name).description(desc).status(status)
            // Part of the security model, not just the sheet: ProjectScopeInterceptor (ADR-021)
            // uses chefProjet as one of the two ways a user may open a project (the other being
            // team membership from section 4). A null chefProjet would lock out the PM who runs it.
            .director(director).chefProjet(chef)
            // Null for the DRAFT projects: an unsigned contract has no client/funder yet.
            .client(client).funder(funder)
            // Must satisfy chk_project_dates (end_date >= start_date, since V17) or the transaction aborts.
            .startDate(LocalDate.parse(start))
            .endDate(LocalDate.parse(end))
            // Read back exactly by sections 6 and 8 to derive every KPI/invoicing amount for this project.
            .initialBudget(new BigDecimal(budget))
            .businessModel(bm).engagementType(et)
            // Fraction (0.38 = 38%), NUMERIC(7,4), same convention as every percentage column in PMS.
            .margeNetteVendue(marge)
            .contractId(contractId)
            // @Builder.Default only applies when the setter isn't called; passing null here would
            // override it, so these two guards keep the "TND" / ONE fallback even then.
            .currency(currency != null ? currency : "TND")
            .exchangeRateToTnd(xRate != null ? xRate : BigDecimal.ONE)
            // Null for DRAFT projects, like client/funder above: nothing has been priced yet.
            .licenseSubcontractBudget(licenseBudget)
            .soldWorkloadDays(soldWl)
            .warrantyWorkloadDays(warrantyWl)
            // Derived from status rather than a 21st argument, so COMPLETED/CANCELLED projects
            // can't be forgotten one by one when splitting the active/archived lists.
            // Known inconsistency: ProjectService.archive() only allows archiving COMPLETED
            // projects through the UI; the seeder bypasses that rule directly.
            .archived(status == ProjectStatus.COMPLETED || status == ProjectStatus.CANCELLED)
            .build());
    }

    /**
     * Shortcut for new BigDecimal(String) - forces every amount through exact decimal
     * arithmetic instead of a double's binary rounding error. String parameter type means
     * bd(0.28) can't compile, only bd("0.28"). Calls always go through String.format(Locale.US,
     * ...) first so a non-US default locale ("14,5") can't throw NumberFormatException here.
     *
     * @param val the number written with a DOT as decimal separator, for example "0.2800".
     */
    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}

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

// =============================================================================
// FILE: DemoDataSeeder.java
//
// WHAT THIS FILE IS
//   A one-shot loader that fills an empty database with a whole believable
//   demonstration company: 35 people, 15 projects, their teams, their planned
//   and their charged man-days, a monthly KPI photograph of every project,
//   business trips with their costs, billing milestones and the payments
//   received for them, risks, deliverables, stakeholders, change requests, and
//   the EMPTY skeleton of the Devis Interne (DI = the internal quote).
//   It writes to 18 tables. It is demonstration data, never production data.
//
// WHERE IT SITS IN THE FLOW
//   No class of the application calls this one. Spring Boot calls it, once, at
//   the very end of the start-up:
//     SpringApplication.run(...)
//       1. Flyway plays the migrations V1..V28. This creates the tables AND
//          inserts the RBAC rows: the roles and the permissions.
//       2. The Spring context is built.
//       3. Spring Boot calls every ApplicationRunner bean, sorted by @Order:
//            DemoDataSeeder        @Order(100)  <- THIS FILE, the first one
//            EnterpriseDataSeeder  @Order(200)  a bigger dataset, prefix ENT-
//            AgileDemoSeeder       @Order(210)  sprints and backlog items
//            DataInitializer       no @Order    so it runs LAST; it creates the
//                                               fixed login accounts
//                                               (admin@pms.local and friends)
//   From here the flow goes straight down to the database: this class calls 18
//   Spring Data repositories, and every save() becomes an INSERT sent by
//   Hibernate to PostgreSQL. It calls no service and no controller.
//
// WHY IT EXISTS - what would be missing without it
//   Delete this file and a fresh "docker compose up" gives a working but
//   completely empty application: every list empty, every chart blank, every
//   KPI at zero. There would be nothing to show to a jury, and no way to test
//   pagination, filters, margin curves or billing reports on realistic volumes.
//
// SECURITY - THE POINTS A JURY ASKS ABOUT FIRST
//   1. The DI is left EMPTY on purpose. The LigneDi rows written in step 13
//      carry structure only: the section, the display order, the label of the
//      contractual profile and the unit. Every money column
//      (coutUnitaireTcc, prixVenteUnitaire, fraisDivers, chargeVendueJh...) is
//      left null. That is the AMENDED DECISION of 2026-07-05: the cost
//      structure of the company is private and is typed by the company itself
//      in the running application.
//   2. This class writes through REPOSITORIES, not through services. The
//      permission checks of PMS sit on the service methods
//      (@PreAuthorize("hasAuthority('X')")), and the project-scope check of
//      ADR-021 sits in ProjectScopeInterceptor, which only sees HTTP requests
//      on /api/projects/{id}/**. At start-up there is no HTTP request and
//      nobody is logged in, so neither gate is crossed here. That is not a hole
//      in the model: this is server-side start-up code, not a request.
//   3. Because nobody is logged in, SpringSecurityAuditorAware returns the
//      string "system", so every row created here carries created_by =
//      "system". That is how a seeded row can later be told apart from a row a
//      real user typed.
//   4. DEMO_PWD below is a password written in clear in the source. It is a
//      demonstration password, published in docs/demo-accounts.md. Unlike
//      DataInitializer, this seeder has NO @ConditionalOnProperty switch to
//      turn it off, so it also runs against a production database. See the
//      note on DEMO_PWD.
//
// IDEMPOTENT - "running it twice must not create everything twice"
//   The guard is the SENTINEL project code DEMO-RH-2025: if a live project
//   already carries that code, the method returns at once and writes nothing.
//   That is what lets the application be restarted as often as needed.
// =============================================================================

/**
 * Fills an empty database with the demonstration dataset: 35 users, 15
 * projects, and all the rows that hang off them.
 *
 * <p>It implements {@link org.springframework.boot.ApplicationRunner} rather
 * than listening to a start-up event, because a runner is called only once the
 * context is fully started and Flyway has finished, and because Spring Boot
 * sorts runners by {@code @Order} - which is exactly what is needed to make
 * this seeder run before the two seeders that build on top of it.
 *
 * <p>Every amount below is a {@link java.math.BigDecimal} built from a String
 * and never a double: a double stores 0.1 as an approximation, so adding a few
 * hundred day costs drifts by a few cents and a total stops matching the sum of
 * the lines shown on the screen.
 */
// @Component asks Spring to build one instance of this class and to keep it in
// the context. Why it is needed: Spring Boot only calls the ApplicationRunner
// objects that are BEANS. Without this line the class is just a class nobody
// ever instantiates - the database stays empty and no error is printed
// anywhere, which is the hardest kind of bug to find.
@Component
// @Order(100) fixes the position of this runner among all the other
// ApplicationRunner beans: the smaller the number, the earlier it runs.
// Why 100: EnterpriseDataSeeder is 200 and AgileDemoSeeder is 210, and both
// need the projects created here to exist already - AgileDemoSeeder reads the
// existing projects in order to hang its sprints on them. Without an order,
// Spring Boot would call the runners in an order nobody controls, and on some
// start-ups AgileDemoSeeder would find no project at all and build empty
// boards. Note that DataInitializer carries no @Order, which gives it the
// lowest priority, so it runs after all three seeders.
@Order(100)
// @RequiredArgsConstructor asks Lombok to write, at compile time, a constructor
// that takes every "private final" field declared below. Spring then uses that
// single constructor to inject the 19 repositories and the password encoder.
// Why constructor injection rather than @Autowired on each field: the fields
// can stay final, so nothing can swap a repository after the object is built,
// and the class can still be created by hand in a unit test with mocks.
// Without this annotation the class has no usable constructor and the context
// fails to start.
@RequiredArgsConstructor
// @Slf4j asks Lombok to add the field "private static final Logger log" to the
// class. Why: the log lines below are the only way to know, when reading the
// start-up console of a server with no interface, whether the dataset was
// generated, skipped because it was already there, or abandoned because the
// RBAC roles were missing. Without it the class would not even compile - the
// name "log" would be unknown.
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {

    // The 20 collaborators of this class. They are all final, so
    // @RequiredArgsConstructor above turns them into constructor parameters and
    // Spring injects them when it builds the bean.
    //
    // 19 of them are Spring Data repositories. 18 are used for WRITING: each
    // save() call in the method below becomes one INSERT. roleRepo is the only
    // one used for READING - the roles were inserted by the Flyway migrations,
    // this class only looks them up.
    //
    // pwdEncoder is the BCryptPasswordEncoder declared in SecurityConfig.
    // BCrypt is a one-way function: it turns a password into a hash that can be
    // checked against a typed password but cannot be turned back into the
    // password. It is used here so that demo accounts are stored exactly like
    // real ones; a column holding a clear password would be a different code
    // path and the login screen would simply not work with it.
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

    // The password given to all 35 demonstration accounts, written in clear in
    // the source.
    //
    // WHY IT IS ACCEPTABLE HERE: these accounts exist only to show the
    // application, and the password is published in docs/demo-accounts.md so
    // that anyone can log in and look around.
    // WHAT TO SAY IF A JURY PUSHES: this is the known weak point of any seeder.
    // DataInitializer, in this same package, can be switched off with the
    // property pms.demo.seed-users=false; this class has no such switch, so on
    // a real deployment the safe move is to delete these accounts or to add the
    // same @ConditionalOnProperty guard.
    // Note that it is encoded ONCE, a few lines below, and that the resulting
    // hash is reused for all 35 users: BCrypt is deliberately slow, which is
    // what makes it resistant to brute force, so 35 separate encode() calls
    // would add seconds to every start-up for no benefit on demo data.
    private static final String DEMO_PWD = "Demo@2026!";
    // The code of the first demonstration project, used as a FLAG: "if a live
    // project already carries this code, the dataset is already there".
    //
    // WHY a sentinel rather than counting rows or keeping a boolean in a
    // settings table: the test must survive a restart, must cost one cheap
    // indexed query, and must not need a table of its own. A project code is
    // already unique among live projects (partial index uk_projects_code,
    // migration V18), so it is a reliable marker.
    // WHAT GOES WRONG WITHOUT IT: every restart would try to add another 15
    // projects, another 35 resources and thousands of charge lines - and the
    // very first insert would be refused by that unique index, so the
    // application would no longer start at all.
    private static final String SENTINEL  = "DEMO-RH-2025";

    /**
     * Generates the whole demonstration dataset in thirteen numbered steps and
     * returns nothing. Spring Boot calls it once, at the end of the start-up,
     * with the command-line arguments of the application - which this seeder
     * ignores, because it has no option to read.
     *
     * <p>Why one long method rather than thirteen private methods: the steps
     * are not independent. Step 4 needs the User objects of step 1, step 5
     * needs the Project objects of step 3, step 8 needs the budget of those
     * same projects. Splitting it would mean passing a dozen lists from method
     * to method, or holding them in fields, which is exactly the hidden state a
     * one-shot loader does not need. The numbered banners below play the role
     * the method names would have played.
     *
     * <p>The order of the steps is the order of the foreign keys: nothing is
     * ever written before the row it points at exists.
     */
    // @Override states that this method implements ApplicationRunner.run. It is
    // not decoration: if the interface signature ever changed, the compiler
    // would point at this line instead of letting the seeder silently stop
    // being called.
    //
    // @Transactional makes the WHOLE generation one single database unit of
    // work. Why it matters here more than anywhere else: this method writes
    // thousands of rows into 18 tables that point at each other. If it failed
    // half way - a constraint refused, the machine short of memory - then
    // without this annotation the database would keep the first half: projects
    // with no team, payments pointing at milestones, and, worst of all, the
    // sentinel project DEMO-RH-2025 would already exist, so the next start-up
    // would decide the dataset was complete and never finish it. With
    // @Transactional everything is rolled back and the next start-up simply
    // tries again from a clean state.
    // It really does apply even though Spring Boot, and not a controller, calls
    // the method: the object Spring Boot picks up from the context is the
    // transactional proxy of this class, not the raw instance.
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // THE IDEMPOTENCE GUARD. "Idempotent" means: running it once and
        // running it ten times give the same result.
        // existsByCodeAndDeletedFalse is a derived query - Spring Data writes
        // the SQL from the method name - and it looks only at live projects
        // (deleted = false), so it uses the partial index uk_projects_code of
        // migration V18 and costs almost nothing.
        // One consequence worth knowing before a jury asks: the test looks at
        // LIVE projects only. If somebody soft-deletes DEMO-RH-2025 from the
        // interface, the next restart regenerates the whole dataset. The users
        // are reused (see the user() helper at the bottom), but the 15 projects
        // and everything hanging off them are created a second time.
        if (projectRepo.existsByCodeAndDeletedFalse(SENTINEL)) {
            log.info("DemoDataSeeder — jeu de données déjà présent, ignoré.");
            return;
        }

        log.info("DemoDataSeeder — génération du jeu de données de démonstration...");

        // Look up the three roles this dataset needs. They are NOT created
        // here: the roles and the permissions are inserted by the Flyway
        // migrations, which have already run by the time this method is called.
        // Why the seeder must not create them: the RBAC model of PMS is
        // dynamic - a role is a bag of permissions, and the migrations decide
        // which permission goes into which bag. A role invented here would have
        // an empty bag, so its users could log in and then be refused
        // everywhere, which simply looks like a broken application.
        // The code looks the roles up BY NAME here only in order to attach a
        // row to a row. No access decision in PMS ever tests a role name:
        // @PreAuthorize always asks for a permission.
        Role roleDir  = role("DIRECTEUR");
        Role roleChef = role("CHEF_PROJET");
        Role roleDev  = role("DEVELOPPEUR");

        // If any of the three roles is missing, stop and log a warning instead
        // of crashing. When this happens: an integration test that builds the
        // schema without playing the RBAC migrations. Without this guard,
        // user(...) would save a User whose role is null, the NOT NULL
        // constraint on users.role_id would abort the transaction, and the
        // start-up would die on a stack trace that says nothing about the real
        // cause.
        if (roleDir == null || roleChef == null || roleDev == null) {
            log.warn("DemoDataSeeder — rôles RBAC introuvables, seeder ignoré.");
            return;
        }

        // Hash the demonstration password ONCE and reuse the result for all 35
        // accounts. encode() applies BCrypt, which is deliberately slow, so
        // calling it inside the creation loop would add a visible pause to
        // every start-up.
        // The side effect, said plainly: the 35 users end up with exactly the
        // same value in password_hash, because BCrypt draws its random salt
        // inside encode(). On real accounts that would be wrong - two people
        // with the same password must not share a hash - but here all 35 share
        // the same published password anyway, and no real secret is involved.
        String pwd = pwdEncoder.encode(DEMO_PWD);

        // ── 1. Users ─────────────────────────────────────────────────────────────

        // Thirty-five accounts spread over the three roles, so the
        // demonstration can show what each kind of user sees. The names are
        // Tunisian, like the company and its clients.
        // The user(...) helper at the bottom of the file reuses an existing
        // account with the same e-mail instead of creating a second one, so
        // this whole block is safe to reach twice.
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

        // One flat list of the 35 users, in a FIXED order: the 5 directors,
        // then the 8 project managers, then the 22 developers. That order is
        // load-bearing - the rates[] and tccs[] arrays just below are read with
        // the same index, so moving a line here would give a developer the
        // daily rate of a director.
        List<User> allUsers = new ArrayList<>();
        allUsers.add(dir1); allUsers.add(dir2); allUsers.add(dir3);
        allUsers.add(dir4); allUsers.add(dir5);
        allUsers.add(cp1); allUsers.add(cp2); allUsers.add(cp3); allUsers.add(cp4);
        allUsers.add(cp5); allUsers.add(cp6); allUsers.add(cp7); allUsers.add(cp8);
        for (User d : devs) allUsers.add(d);

        List<Resource> resources = new ArrayList<>();
        // The cost sheet of every person, cell by cell against allUsers above.
        //
        //   rates[i] = what one working day of that person costs the company,
        //              in TND, before overheads.
        //   tccs[i]  = the TCC coefficient, stored as a FRACTION and not as a
        //              percentage: 0.2800 means 28 percent, and a loaded day
        //              costs dailyRate multiplied by (1 + tccRate). Writing 28
        //              instead of 0.28 here would multiply every cost by 29.
        //
        // Directors are the dearest and developers the cheapest, which is what
        // makes the margin figures of the demonstration look credible.
        //
        // CAREFUL, a real detail of this code: both arrays hold 34 values while
        // allUsers holds 35. The Math.min(...) in the loop below clamps the
        // index, so the 35th person reuses the 34th rate. Nothing crashes, but
        // that clamp is not decoration - see the comment on it.
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

        // For every user, build one Resource (the cost sheet) and three
        // TccAnnuel rows (the rates of 2023, 2024 and 2025).
        // Why two tables rather than more columns on Resource: the cost of a
        // man-day depends on the YEAR the day was charged to, because the rates
        // are renegotiated every year (spec F-AFF-13 section 6.3 rule 4).
        // KpiService prices each charge line with the rate of its own year and
        // falls back to the Resource rates when that year has no row. Seeding
        // three years is what makes the rule visible on a project that runs
        // across 2024 and 2025.
        for (int i = 0; i < allUsers.size(); i++) {
            User u = allUsers.get(i);
            // Math.min(i, rates.length - 1) clamps the index to the last cell.
            // Why it is needed: there are 35 users but only 34 rates, so i
            // reaches 34 while the last valid index is 33. Without the clamp
            // the loop would end on ArrayIndexOutOfBoundsException and the
            // application would not start at all.
            BigDecimal rate = rates[Math.min(i, rates.length - 1)];
            BigDecimal tcc  = tccs[Math.min(i, tccs.length - 1)];
            // staffingStart is 1 January 2022, well before the oldest project
            // of the dataset (DEMO-SI-2024 starts in June 2024). Why: the
            // staffing window says from when a person may be put on a project,
            // so a start date later than a project would make the workload
            // screens refuse perfectly normal demo rows.
            // staffingEnd is left unset on purpose: these people are still
            // available, and a conventional far-away date such as 31/12/2099
            // would be read as a real end date by anyone writing a report.
            Resource res = resourceRepo.save(Resource.builder()
                .user(u)
                .dailyRate(rate)
                .tccRate(tcc)
                .staffingStart(LocalDate.of(2022, 1, 1))
                .build());
            resources.add(res);
            // Three yearly rate rows per resource - 2023, 2024, 2025 - which
            // are the years the demonstration projects run across.
            // The figures move on purpose: 2023 is 5 percent cheaper than the
            // base rate and one point of TCC lower, 2024 is the base itself,
            // 2025 is 5 percent dearer and one point higher. That is what makes
            // the yearly-rate rule visible - the same man-day does not cost the
            // same in 2024 and in 2025.
            // setScale(2, HALF_UP) and setScale(4, HALF_UP) trim the result of
            // the arithmetic to the number of decimals the column really holds:
            // NUMERIC(10,2) for a daily rate, NUMERIC(5,4) for a coefficient.
            // Without it the BigDecimal held in memory could carry more
            // decimals than the row on disk, and the two would disagree.
            // HALF_UP is the rounding a human expects: 0.125 becomes 0.13.
            // The loop visits each year exactly once, which matters because V21
            // puts a unique index on (resource_id, annee) over the live rows:
            // writing 2024 twice for one resource would be refused.
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

        // Fifteen projects, written out one by one rather than generated in a
        // loop, because each one is a deliberate demonstration case: real
        // Tunisian client names, budgets from 180 000 to 1 500 000 TND, and all
        // five values of ProjectStatus present.
        //   ACTIVE    (10 projects) - the normal working case;
        //   COMPLETED (p09, p10)    - finished, and archived by the helper;
        //   ON_HOLD   (p08)         - paused, still expected to come back;
        //   CANCELLED (p14)         - stopped for good, and archived;
        //   DRAFT     (p15)         - not started, no client named yet.
        // Why all five: several screens behave differently per status - the
        // active list hides archived projects, and the workload and KPI steps
        // below skip DRAFT and CANCELLED - so a demonstration made only of
        // ACTIVE projects would show none of that behaviour.
        // Two business axes are varied too: BusinessModel (SEUL = the company
        // alone, GROUPEMENT = a consortium) and EngagementType (FORFAIT = a
        // fixed price for an agreed scope, REGIE = the client pays the days
        // actually worked).
        // The last argument, margeNetteVendue, is the sold net margin stored as
        // a fraction: 0.38 means 38 percent. It is only the FALLBACK baseline -
        // when a project has DI lines, the margin computed from the quote wins.
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

        // List.of(...) builds a fixed, unmodifiable list. Why not a new
        // ArrayList: nothing below must ever add a project to it, and an
        // unmodifiable list turns a mistake into an immediate
        // UnsupportedOperationException instead of a dataset that silently
        // grows. The ORDER matters here too - the teams[] and projectDays[]
        // arrays below are read with the same index.
        List<Project> allProjects = List.of(p01,p02,p03,p04,p05,p06,p07,p08,p09,p10,p11,p12,p13,p14,p15);

        // ── 4. TeamAssignments ───────────────────────────────────────────────────

        // Fill the project teams. The chef de projet is already tied to the
        // project by the chefProjet foreign key of step 3; this step writes
        // the TeamAssignment rows.
        // Which developers work on which project, one row per project, in the
        // same order as allProjects. The teams overlap on purpose - devs[0]
        // appears on p01, p03 and p11 - because a realistic workload screen
        // has to show somebody split across several projects.
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
        // The job titles handed out to team members, cycled by the modulo
        // below. roleInTeam is a free label shown on the team screen; it is NOT
        // a security role and it grants nothing at all. What a user may do
        // comes from his RBAC role and from nowhere else.
        String[] teamRoles = {"Développeur Backend","Développeur Frontend","Développeur Full-Stack",
            "Analyste fonctionnel","Testeur QA","Architecte","Consultant","Scrum Master"};

        for (int i = 0; i < allProjects.size(); i++) {
            Project prj = allProjects.get(i);
            User chef = prj.getChefProjet();
            // The project manager is already tied to the project by the
            // chef_projet_id foreign key set in step 3. He is ALSO written into
            // team_assignments here, with the label "Chef de Projet" and the
            // exact dates of the project, so that the team list of the project
            // screen shows the person in charge among the people staffed on it.
            // The two links are not redundant: chef_projet_id says who is
            // responsible, a TeamAssignment row says who is staffed, with which
            // title and between which dates. For the access perimeter of
            // ADR-021 either link is enough - the query
            // findAccessibleProjectIdsByEmail accepts "chef de projet OR team
            // member".
            // The null test exists because a DRAFT project may have no manager
            // yet. Here they all do, but a NullPointerException at start-up
            // would stop the whole application, so the test costs nothing.
            if (chef != null) {
                teamRepo.save(TeamAssignment.builder()
                    .project(prj).user(chef).roleInTeam("Chef de Projet")
                    .startDate(prj.getStartDate()).endDate(prj.getEndDate())
                    .build());
            }
            // The guard "i < teams.length" reads the team of project i only
            // when that row really exists, and hands back an empty array
            // otherwise. Today teams has exactly 15 rows, one per project, so
            // the else branch is never taken. It is kept because adding a
            // sixteenth project in step 3 and forgetting to add its team row
            // here would otherwise end the start-up on
            // ArrayIndexOutOfBoundsException, instead of simply creating a
            // project that has no developer staffed on it yet.
            User[] members = i < teams.length ? teams[i] : new User[0];
            // teamRoles[j % teamRoles.length] walks the titles and starts
            // again from the first one when a team has more members than there
            // are titles. Without the modulo, the ninth member of a team would
            // end the start-up on ArrayIndexOutOfBoundsException.
            for (int j = 0; j < members.length; j++) {
                teamRepo.save(TeamAssignment.builder()
                    .project(prj).user(members[j])
                    .roleInTeam(teamRoles[j % teamRoles.length])
                    .startDate(prj.getStartDate()).endDate(prj.getEndDate())
                    .build());
            }
        }

        // ── 5. PlanCharge & ChargeReelle ─────────────────────────────────────────

        // The heart of the demonstration: the man-days. Two tables.
        //   PlanCharge   = what was PLANNED for one person, one project, one
        //                  month.
        //   ChargeReelle = what that person really CHARGED that month, once the
        //                  timesheet was submitted and validated.
        // The gap between the two is what every margin, every consumption rate
        // and every drift figure of the KPI screen is built on. Without this
        // step the application would have projects and teams but no numbers.
        // Per-project base JH/month — drives financial scenarios:
        // Index:    0     1     2     3     4     5     6     7     8     9    10    11    12    13    14
        // Project: RH   SI  MOBIL   BI   ERP  CYBER CLOUD  IOT  ARCH   GED DIGIT E-GOV MAINT FORMA DRAFT
        // Margin:  LOSS PROF  LOSS LOSS PROF  PROF  PROF  LOSS  ~0  PROF  LOSS PROF  LOSS   --   --
        // One base figure per project: how many man-days a month each team
        // member charges. It is the knob that decides whether a project ends up
        // looking profitable - more days charged against the same sold budget
        // means a smaller margin. The table above records the intent project by
        // project: LOSS, PROFitable, or roughly break-even.
        double[] projectDays = {22.0, 14.0, 23.0, 20.0, 11.0, 13.0, 17.0, 24.0, 18.0, 10.0, 21.0, 15.0, 19.0, 18.0, 15.0};

        for (int i = 0; i < allProjects.size(); i++) {
            Project prj = allProjects.get(i);
            // DRAFT and CANCELLED projects get no workload at all. Why: a draft
            // has not started, and a cancelled project must not go on producing
            // costs. A cancelled project carrying charged days would display a
            // margin on a screen where the honest answer is "nothing to show".
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            // Same defensive read as in step 4: the team row is used only if
            // it exists. A project with nobody on it is then skipped, because
            // the month loop below turns on the members: with an empty team it
            // would walk every month of the project and write nothing, which is
            // pure waste at start-up.
            User[] members = i < teams.length ? teams[i] : new User[0];
            if (members.length == 0) continue;

            // The three lines below are all "use the real value, and fall back
            // to a sane default if it is missing". None of the defaults is ever
            // reached with the current dataset - every project of step 3 has a
            // base figure, a manager and two dates - but a null here would stop
            // the whole application from starting, and that is a poor way to
            // learn that a project was created without an end date.
            //
            // baseDays: how many man-days a month each member charges. 18.0 is
            // the middle of the projectDays[] range above.
            double baseDays = i < projectDays.length ? projectDays[i] : 18.0;
            // validator: who approved the timesheet. charges_reelles.validated_by
            // is a NULLABLE column, so leaving it empty would not be refused by
            // the database - but a line that is validated (validatedAt set) and
            // yet validated by nobody is a contradiction the screens would
            // display as an empty "validated by" cell. dir1 is used as a
            // stand-in only when a project has no manager at all.
            User validator  = prj.getChefProjet() != null ? prj.getChefProjet() : dir1;

            LocalDate start  = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate end    = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2026, 12, 31);
            // Walk the project month by month. withDayOfMonth(1) is the
            // CONVENTION of the two tables: a period is always stored as the
            // first day of its month. It matters because V7 puts a unique index
            // on (project_id, user_id, period): if one piece of code stored the
            // 15th and another the 1st of the same month, the index would not
            // see them as the same month, the same person would end up with two
            // plans for March, and that month would be counted twice in every
            // total.
            LocalDate cursor = start.withDayOfMonth(1);

            while (!cursor.isAfter(end)) {
                for (User dev : members) {
                    // A small wobble from one month to the next so the curves
                    // on the charts are not perfectly flat lines. Pure
                    // cosmetics - it carries no business rule.
                    double delta = (cursor.getMonthValue() % 3 == 0) ? 1.5 : (cursor.getMonthValue() % 3 == 1) ? -1.0 : 0.5;
                    // String.format with Locale.US forces a DOT as the decimal
                    // separator before the text is handed to new BigDecimal().
                    // Why it is not optional: on a machine whose default locale
                    // is French, "%.2f" of 22.5 produces "22,50" with a comma,
                    // and new BigDecimal("22,50") throws NumberFormatException.
                    // The application would then start fine on the developer's
                    // machine and die at start-up on the server.
                    BigDecimal planned = bd(String.format(Locale.US, "%.2f", baseDays + delta));
                    // A ceiling worth knowing before a jury asks why the
                    // figures above stop where they do: V7 puts the check
                    // chk_pc_days (planned_days > 0 AND <= 31) on plan_charges
                    // and chk_cr_days (actual_days >= 0 AND <= 31) on
                    // charges_reelles - a month cannot hold more than 31 days
                    // of work. The largest base in projectDays[] is 24, the
                    // wobble adds at most 1.5, and the correction factor of the
                    // charged line peaks at 1.03, so the biggest value written
                    // here is about 26.3. Raising a figure in projectDays[]
                    // past roughly 29 would make the database refuse the insert
                    // and the application would not start.
                    planRepo.save(PlanCharge.builder()
                        .project(prj).user(dev).period(cursor).plannedDays(planned)
                        .build());

                    // Only months already in the past get a charged line. Why
                    // compare with LocalDate.now() rather than with a fixed
                    // date: the demonstration has to still look alive next
                    // year. A future month keeps its plan and has no actuals,
                    // which is exactly what a real timesheet looks like.
                    // These are the rows that fill budgetConsome on the KPI
                    // screen.
                    if (cursor.isBefore(LocalDate.now())) {
                        // The charged days are the planned days multiplied by a
                        // factor between 0.92 and 1.03, picked from a fixed
                        // list. Choosing it from the month and the project index
                        // rather than from a random number makes the dataset
                        // DETERMINISTIC: the same database is rebuilt
                        // identically every time, so a screenshot taken for the
                        // report still matches the application months later.
                        BigDecimal factor = bd(pickFromArray(
                            new String[]{"0.92","0.97","1.00","1.03","0.95","0.98","1.01","0.96"},
                            cursor.getMonthValue() + i + 1));
                        BigDecimal actual = planned.multiply(factor).setScale(2, java.math.RoundingMode.HALF_UP);
                        // submittedAt on day 25 and validatedAt on day 28 of
                        // the same month reproduce the real cycle: the person
                        // sends the timesheet at the end of the month and the
                        // project manager validates it a few days later.
                        // atStartOfDay() is needed because those two columns are
                        // timestamps while cursor is a plain date.
                        // Filling them is not cosmetic: KpiService reads the
                        // charge lines through findValidatedByProjectId, whose
                        // query says "validatedAt IS NOT NULL". A line left
                        // unvalidated would exist in the table and weigh nothing
                        // at all in the KPI figures.
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

        // A monthly photograph of each project: budget planned, budget spent,
        // estimate at completion, margin, percentage of work earned, days
        // consumed and days left. One row per project per past month.
        //
        // WHY THESE FIGURES ARE STORED AND NOT COMPUTED ON THE FLY: this table
        // is the HISTORY. KpiService can always recompute today's figure from
        // the charge lines, but it cannot recompute what the project looked
        // like last March once the plan has been revised. Without snapshots,
        // every "margin over time" curve of the dashboard would be a flat line
        // at today's value.
        // Note that this does not contradict the rule of the Devis Interne,
        // where an amount is always derived when read and never stored: a
        // snapshot is not a computed amount, it is a dated observation.
        //
        // "faits marquants" are the highlights of the month, free text. The
        // sentences below are cycled so the dashboard is not filled with the
        // same line over and over.
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
            // DRAFT projects are skipped: a project that never started has no
            // history to photograph. CANCELLED projects are kept here, unlike
            // in step 5, because a cancelled project did exist for a while and
            // its past snapshots are part of its story.
            if (prj.getStatus() == ProjectStatus.DRAFT) continue;
            LocalDate start = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate end   = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2025, 12, 31);
            LocalDate cursor = start.withDayOfMonth(1);
            int snap = 0;
            BigDecimal budget = prj.getInitialBudget() != null ? prj.getInitialBudget() : bd("500000");

            // Two conditions at once: stay inside the project window AND stay
            // in the past. A snapshot dated in the future would mean the
            // application had observed something that has not happened yet, and
            // the dashboard curve would run past today.
            while (!cursor.isAfter(end) && cursor.isBefore(LocalDate.now())) {
                // Progress goes 5 %, 13.5 %, 22 % and so on, one snapshot after
                // the other, and Math.min stops it at 100. EV (earned value) is
                // the share of the work actually earned, in percent. Without
                // that cap a long project would report 137 percent done; the
                // column ev_pct is NUMERIC(5,2) so the value would still fit,
                // nothing would crash, and the dashboard would simply show an
                // impossible figure.
                double pct = Math.min(100.0, snap * 8.5 + 5.0);
                BigDecimal evPct       = bd(String.format(Locale.US, "%.2f", pct));
                BigDecimal budgetPlan  = budget.multiply(bd(String.format(Locale.US, "%.4f", pct / 100.0))).setScale(2, java.math.RoundingMode.HALF_UP);
                // The spent budget is fixed at 93 percent of the planned
                // budget, so every snapshot shows a 7 percent margin - which is
                // also why margeActuellePct below is the constant 0.0700. These
                // are chosen demonstration figures, not a computation: the real
                // margin of a project is worked out by KpiService from the
                // validated charge lines and the resource rates.
                BigDecimal budgetConso = budgetPlan.multiply(bd("0.93")).setScale(2, java.math.RoundingMode.HALF_UP);
                BigDecimal marge       = budgetPlan.subtract(budgetConso);
                BigDecimal caProduction = budget.multiply(bd(String.format(Locale.US, "%.4f", pct / 100.0))).setScale(2, java.math.RoundingMode.HALF_UP);

                // EAC (estimate at completion) is what the project is now
                // expected to cost in the end; here 102 percent of the initial
                // budget, so every project shows a small overrun.
                // caProduction is the revenue earned by the work done so far,
                // totalFacture the part of it already invoiced (70 percent), and
                // fae the "facture a etablir": earned but not yet invoiced (the
                // remaining 30 percent). caProduction = totalFacture + fae,
                // which is the identity the billing screen relies on.
                // dateFinEstimee slips by 15 days on every other snapshot, so
                // the demonstration shows both the on-time and the late case.
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

        // Missions are business trips: somebody goes to a client site for a few
        // days. Each mission carries its cost lines - transport, accommodation,
        // per diem - in a second table, ComposanteMission.
        //
        // WHY TWO TABLES RATHER THAN THREE COLUMNS ON THE MISSION: the list of
        // cost kinds is not closed, and one mission can have several lines of
        // the same kind. Columns transport/sejour/perdiem would need a migration
        // the day a fourth kind appears, and could never hold two transport
        // lines. TypeComposante is an enum, so a typo such as "perdim" cannot be
        // stored in the first place.
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

        // Only the first eleven projects get missions. Leaving p12 to p15
        // without any is deliberate: the mission screen has to be shown in its
        // empty state as well.
        List<Project> missionProjects = List.of(p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11);
        int mIdx = 0;
        for (Project prj : missionProjects) {
            // 3, 4 or 5 missions per project, decided by the project index so
            // that the result is identical on every rebuild.
            int nbMissions = 3 + (mIdx % 3); // 3-5 missions per project
            User[] members = mIdx < teams.length ? teams[mIdx] : new User[]{devs[0]};
            for (int m = 0; m < nbMissions; m++) {
                // Missions are spaced two months apart, the first one landing
                // one month after the project starts: month 1, then 3, then 5.
                // Why not from the very first month: a trip to the client site
                // in the opening days of a project, before anything is framed,
                // is not a believable demonstration case.
                // The L in "m * 2L" makes the value a long, which is the type
                // plusMonths expects. Without it the int would be widened
                // anyway, so this is a habit rather than a need - but the same
                // expression with a large multiplier would silently overflow.
                LocalDate mStart = (prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 6, 1))
                    .plusMonths(m * 2L + 1);
                // The end date is 2, 3 or 4 days after the start day, so a
                // mission covers 3 to 5 calendar days counting both ends. The
                // "m % 3" is what varies the length from one mission to the
                // next, so the list is not fifteen identical trips.
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
                // Two or three cost lines per mission: transport and
                // accommodation always, a per diem every other mission.
                // These amounts are ordinary travel expenses in TND, not the
                // cost structure of the company, so there is no reason to
                // leave them out.
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

        // The money side of a project, in three tables.
        //   JalonFacturation = a billing milestone: "once this is delivered, we
        //                      invoice X percent of the contract";
        //   Paiement         = the money actually received for an invoiced
        //                      milestone;
        //   Avenant          = a contract amendment: extra scope, extra money,
        //                      extra days.
        // Three milestone templates are used in turn, so the demonstration
        // shows contracts cut into 5 and into 6 milestones instead of one shape
        // repeated fifteen times. The percentages of each template add up to
        // 100, which is the rule the billing screen checks.
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

        // Twelve of the fifteen projects get a billing plan. p08 (ON_HOLD),
        // p14 (CANCELLED) and p15 (DRAFT) are left out, again so that the empty
        // state of the billing screen can be shown.
        List<Project> billingProjects = List.of(p01,p02,p03,p04,p05,p06,p07,p09,p10,p11,p12,p13);
        int bIdx = 0;
        for (Project prj : billingProjects) {
            BigDecimal budget = prj.getInitialBudget() != null ? prj.getInitialBudget() : bd("500000");
            LocalDate pStart  = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate pEnd    = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2025, 12, 31);

            // Pick one of the three milestone templates, cycling with the
            // project counter: project 0 takes template 0, project 1 template
            // 1, project 3 template 0 again.
            // The SAME index is used to read the labels and the percentages, so
            // a template always keeps its own shares. Reading jalonLabels[0]
            // together with jalonPcts[1] would give the six-milestone contract
            // only five percentages, and the last milestone would fall back on
            // the first share through the modulo below - a contract adding up
            // to 110 percent.
            int tmpl = bIdx % jalonLabels.length;
            String[] labels = jalonLabels[tmpl];
            double[] pcts   = jalonPcts[tmpl];
            // ChronoUnit.DAYS.between gives the number of whole days between
            // the two dates. It is used just below to place each milestone at
            // its share of the contract: milestone j of n falls at (j + 1) / n
            // of the way through. Spacing the milestones by fixed months
            // instead would push the last one past the end of a short
            // contract.
            long durationDays = java.time.temporal.ChronoUnit.DAYS.between(pStart, pEnd);

            // Collects the milestone rows of this project as they are saved.
            // Being honest about it, because a jury may well ask: nothing reads
            // this list afterwards. The payment a few lines below is written
            // from the "jalon" variable of the current turn of the loop, not
            // from the list. It is a leftover that costs one small object per
            // project and changes no behaviour.
            List<JalonFacturation> jalons = new ArrayList<>();
            for (int j = 0; j < labels.length; j++) {
                // Where this milestone falls inside the contract: milestone j
                // of n lands at (j + 1) / n of the way through.
                // The cast (double) is not decoration. j + 1 and labels.length
                // are both int, and in Java int / int is an INTEGER division:
                // 1 / 5 would be 0, and 4 / 5 would be 0 as well. Every
                // milestone but the last would then be dated on the very first
                // day of the project. The cast forces a real division, so
                // ratio really goes 0.2, 0.4, 0.6, 0.8, 1.0.
                double ratio = (double)(j + 1) / labels.length;
                LocalDate datePrevue = pStart.plusDays((long)(durationDays * ratio));
                // A milestone counts as already invoiced when it falls before
                // 1 July 2025. Note that this is a FIXED date and not
                // LocalDate.now(): the split between invoiced and still-planned
                // milestones does not move as time passes, unlike the charge
                // lines of step 5, which do compare against today.
                boolean isPast = datePrevue.isBefore(LocalDate.of(2025, 7, 1));
                JalonStatut statut = isPast ? JalonStatut.FACTURE : JalonStatut.PREVU;
                LocalDate dateFacture = isPast ? datePrevue.plusDays(7) : null;

                // pourcentage is the share of the contract this milestone
                // carries and montant is that share applied to the budget. Both
                // are written: the percentage is what the contract says, the
                // amount is what gets invoiced, and keeping the percentage means
                // the figure can still be explained after a budget revision.
                // datePrevue is always filled, while dateFacture stays null for
                // a milestone not yet invoiced - that null is how the screen
                // tells "planned" from "done".
                //
                // "pcts[j % pcts.length]" rather than plain "pcts[j]": the loop
                // counts on the LABELS, so the modulo is what keeps the read
                // inside the percentages array if the two ever stopped having
                // the same length. Today every template has as many shares as
                // labels (6 and 6, then 5 and 5, then 5 and 5), so the modulo
                // never wraps and the shares of a contract add up to exactly
                // 100 - which is the rule the billing screen checks. Adding a
                // seventh label to the first template without adding its share
                // would, thanks to this modulo, reuse the first share instead
                // of crashing, and the contract would quietly total 110.
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

                // A payment row is written only for milestones already
                // invoiced. The extra test on the amount guards against a null
                // montant: paiements.montant_recu is NOT NULL, so a null here
                // would abort the whole seeding transaction.
                // The money arrives 14 days after the invoice, the usual
                // payment term, and the reference follows the shape
                // VIR-<project code>-J<milestone number>-<year>; VIR stands for
                // "virement", a bank transfer.
                if (statut == JalonStatut.FACTURE && jalon.getMontant() != null) {
                    paiementRepo.save(Paiement.builder()
                        .jalon(jalon)
                        .montantRecu(jalon.getMontant())
                        .datePaiement(dateFacture.plusDays(14))
                        .reference("VIR-" + prj.getCode() + "-J" + (j + 1) + "-" + dateFacture.getYear())
                        .build());
                }
            }

            // One project in three gets a first amendment, and one in five
            // that is still ACTIVE gets a second one. Why not one for
            // everybody: an amendment is an exception in real life, and the
            // screens have to be shown both with and without one. The
            // CANCELLED test avoids the absurd case of extending a contract
            // that was stopped.
            // The first amendment adds 12 percent of the budget and 45
            // man-days, the second 8 percent and 25 man-days.
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

        // Project risks: what could go wrong, how likely it is, how bad it
        // would be, what is being done about it, and where the risk stands
        // today. Probability and impact both use the NiveauRisque enum
        // (FAIBLE / MOYEN / ELEVE) - the same three levels on both axes, which
        // is what lets the interface draw the classic probability/impact
        // matrix. Three sets of descriptions are rotated so that neighbouring
        // projects do not show the same list.
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
            // DRAFT and CANCELLED projects get no risk at all. A risk is
            // something that may still hurt the project, so it only makes sense
            // while the project is alive: a draft has not started, and nothing
            // can go wrong any more on a project that was stopped. Seeding an
            // OUVERT (open) risk on a cancelled project would put a line on the
            // "risks to follow" screen that nobody can ever close.
            // Note that step 10 makes the OPPOSITE choice for deliverables and
            // skips DRAFT only - see the comment there.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            // Rotate the three description sets with the project counter, so
            // two projects side by side in the list do not show the same risks.
            String[] rDescs = riskData[rIdx % riskData.length];
            // The modulo walks each array and starts again at its beginning, so
            // probability, impact and status change from one line to the next.
            // Impact is shifted by one against probability (rIdx + r + 1),
            // which spreads the pairs over the matrix instead of lining every
            // risk up on its diagonal.
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

        // Deliverables: the documents and products the contract promises, each
        // with a due date and a status.
        // Only DRAFT projects are skipped here. CANCELLED ones keep their
        // deliverables, unlike their risks, and that is deliberate: what had
        // been produced before a project was stopped still exists.
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
                // The status is derived from the due date instead of being
                // drawn at random: anything due before 2025 is LIVRE
                // (delivered), the first half of 2025 alternates LIVRE and
                // EN_COURS (in progress), and later dates alternate EN_ATTENTE
                // (waiting) and VALIDE (approved).
                // As with the billing milestones above, these two cut-off dates
                // are fixed and are not LocalDate.now().
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

        // Stakeholders: the people on the CLIENT side who matter to the
        // project, with how much influence they have and how much interest they
        // take in it. Those two axes are what the interface uses to draw the
        // influence/interest grid that says who to keep informed and who to
        // manage closely. Both reuse the NiveauRisque enum, so the grid always
        // has the same three levels on both sides.

        // A RECORD declared INSIDE the method (records exist since Java 16; PMS
        // runs on Java 21). A record is a small immutable class: from this one
        // line the compiler writes the constructor, the accessors, equals and
        // hashCode.
        // Why here and not in a file of its own: this shape is used by the
        // twenty lines that follow and by nothing else in the application.
        // Putting it in com.pms.governance would suggest it is part of the
        // domain model, which it is not - it is a seeding helper.
        // Why a record rather than five parallel arrays: the five values of one
        // template stay together, so it is impossible to add a template and
        // forget its phone number.
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
            // DRAFT and CANCELLED projects get no stakeholder. A draft has no
            // client named yet - look at p15 in step 3, its client is null - so
            // there is nobody on the other side to list, and the e-mail domain
            // built a few lines below would be addresses of a company that was
            // never chosen. On a cancelled project the people are simply no
            // longer to be contacted.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            // Between 2 and 5 stakeholders per project. Math.min never lets the
            // loop run past the end of ppTemplates: today nbPP can only reach
            // 5, which is exactly the number of templates, but the guard means
            // that raising nbPP later cannot break the start-up.
            int nbPP = 2 + (ppIdx % 4);
            String[] noms = ppNoms[ppIdx % ppNoms.length];
            // Build a plausible e-mail domain out of the project code:
            // "DEMO-RH-2025" becomes "rh-2025", so the stakeholder addresses
            // read dg@rh-2025.tn, dsi@rh-2025.tn and so on. These are invented
            // addresses on invented domains; nothing is ever sent to them.
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

        // Change requests: what the client asked for after the contract was
        // signed, and what was decided about it.
        // All three statuses are represented on purpose - the first request of
        // a project is APPROUVE, the second EN_ATTENTE, the third REJETE -
        // because the screen shows them in different colours and a demo made
        // only of approved requests would show a single one of them.
        // dateDecision stays null while the request is EN_ATTENTE, which is the
        // rule: a decision date with no decision behind it means nothing.
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
            // Same rule as for the risks: a change request only makes sense on
            // a live contract. Nobody asks for a new feature on a project that
            // has not started or that was cancelled.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            // 1, 2 or 3 requests per project, decided by the project counter so
            // that the result is the same on every rebuild. The count matters:
            // a project with only one request shows the APPROUVE case alone, and
            // it takes a project with three to show all three statuses at once.
            int nbDC = 1 + (dcIdx % 3);
            // Who asked. The project manager stands in for the client here,
            // because demandes_changement.demandeur_id points at a USER of the
            // application, and clients have no account in PMS.
            // The dir1 fallback is not cosmetic on this one: that foreign key
            // is declared nullable = false, so a project with no manager would
            // send null, the database would refuse the insert, and the whole
            // seeding transaction would roll back at start-up.
            User demandeur = prj.getChefProjet() != null ? prj.getChefProjet() : dir1;
            LocalDate pStart = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            for (int dc = 0; dc < nbDC; dc++) {
                // The priority follows the position of the request: the first
                // one is ELEVEE (high), the second NORMALE, the third FAIBLE
                // (low). dc never reaches 3, so the modulo never wraps; it is
                // written this way so that raising nbDC later keeps cycling
                // instead of failing.
                PrioriteChangement prio = dc % 3 == 0 ? PrioriteChangement.ELEVEE : dc % 3 == 1 ? PrioriteChangement.NORMALE : PrioriteChangement.FAIBLE;
                // The status also follows the position, and this is the line
                // that guarantees all three colours of the screen are shown.
                StatutChangement sc = dc == 0 ? StatutChangement.APPROUVE : dc == 1 ? StatutChangement.EN_ATTENTE : StatutChangement.REJETE;
                // Requests land two months after the project starts, then every
                // two months. "dc * 2L" is a long because plusMonths takes a
                // long; the int 2 beside it is widened to long by the
                // multiplication, so the whole expression is already a long.
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
        // SECURITY. These are the most sensitive twenty lines of the file.
        //
        // The Devis Interne (DI = the internal quote) is the sheet where the
        // company compares what it sold on a project with what the work really
        // costs it. AMENDED DECISION of 2026-07-05: the STRUCTURE of a quote
        // may be seeded, its VALUES never. So only the section, the display
        // order, the label of the contractual profile and the unit are written
        // here. Every money column - coutUnitaireTcc, prixVenteUnitaire,
        // fraisDivers, fraisGeneraux, coutImpots, chargeVendueJh,
        // quantiteInterneJh, tauxPourcentage - stays null, and the company
        // types its own figures in the running application.
        // What would go wrong otherwise: realistic-looking daily costs and
        // margins committed to a repository would expose, or appear to expose,
        // the real cost structure of the company.
        // Note also that the DI never stores a computed amount anyway:
        // DevisInterneService derives every amount and every margin when the
        // quote is READ (spec F-AFF-13), so that correcting an exchange rate
        // immediately corrects every line instead of leaving stale figures
        // behind.

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
        // The three sections of a quote and what goes in each:
        //   HONORAIRES   = the fee lines, one per contractual profile, counted
        //                  in man-days (H-Jour);
        //   FRAIS        = travel and accommodation expenses;
        //   AUTRES_FRAIS = taxes, provisions and registration costs, expressed
        //                  as a percentage rather than in days - which is why
        //                  their unit is "%" further down.

        // The two arrays are read with the SAME index s: diSections[s] is the
        // section that the labels of diProfils[s] belong to. Reordering one
        // without the other would file the travel expenses under HONORAIRES.
        SectionDi[] diSections = {SectionDi.HONORAIRES, SectionDi.FRAIS, SectionDi.AUTRES_FRAIS};

        // Nine of the fifteen projects get a quote skeleton. The other six are
        // left with no DI line at all, and that is a useful demonstration case
        // in itself: KpiService falls back to the manually typed
        // Project.margeNetteVendue exactly when a project has no DI line.
        List<Project> diProjects = List.of(p01, p02, p03, p04, p05, p07, p09, p11, p12);
        for (Project prj : diProjects) {
            for (int s = 0; s < diSections.length; s++) {
                String[] profils = diProfils[s];
                for (int l = 0; l < profils.length; l++) {
                    ligneDiRepo.save(LigneDi.builder()
                        .project(prj)
                        .section(diSections[s])
                        // ordre is 1-based and decides the display order inside
                        // the section. The column defaults to 0, so leaving it
                        // unset would pile every line of a section at the same
                        // rank and the quote would come out in whatever order
                        // the rows happened to be returned in.
                        .ordre(l + 1)
                        .profilContractuel(profils[l])
                        .ressourceProposee(null)   // no name: real staffing is company data
                        .ressourceRetenue(null)    // idem, the company fills it in itself
                        // AUTRES_FRAIS lines are a percentage (a provision for
                        // risk of 5 percent), the other two sections are counted
                        // in man-days. The unit is only a label: it is the
                        // section and the tauxPourcentage field that tell
                        // DevisInterneService how to price a line.
                        .unite(diSections[s] == SectionDi.AUTRES_FRAIS ? "%" : "H-Jour")
                        // Every financial column is left null on purpose
                        // (decision of 2026-07-05). See the SECURITY note at the
                        // top of this section.
                        .build());
                }
            }
        }

        // The closing line of the start-up log. On a server with no interface
        // it is the only proof that the generation ran all the way to the end
        // rather than being rolled back half way.
        log.info("DemoDataSeeder — terminé. {} utilisateurs, {} ressources, {} projets créés.",
            allUsers.size(), resources.size(), allProjects.size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Looks a role up by its name and gives back null when it does not exist,
     * instead of throwing.
     *
     * <p>Why null and not an exception: the caller above uses that null to log
     * a clear warning and stop the seeding politely. A database without the
     * RBAC rows is a normal situation in some integration tests, and a demo
     * seeder is not a reason to bring the whole application down.
     *
     * <p>Reading a role BY NAME here is not a security decision - it only links
     * a user row to a role row. No access check in PMS ever tests a role name;
     * {@code @PreAuthorize} always asks for a permission.
     */
    private Role role(String name) {
        return roleRepo.findByName(name).orElse(null);
    }

    /**
     * Gives back the account carrying this e-mail if it already exists, and
     * creates it otherwise. Returns the User in both cases.
     *
     * <p>This is what makes the user part of the seeder safe to run twice. The
     * e-mail is unique among live accounts (partial index uk_users_email,
     * migration V18), so trying to create a second Sami Mansouri would be
     * refused by the database and would abort the whole start-up.
     *
     * <p>Why orElseGet and not orElse: orElse EVALUATES its argument every
     * time, even when the account was found, so a duplicate User would be built
     * and saved on every single call. orElseGet takes a lambda and runs it only
     * when the Optional is empty. That one word is the difference between an
     * idempotent helper and a seeder that doubles its users on every restart.
     *
     * <p>firstLogin is set to false so that the demonstration accounts go
     * straight into the application: with the default true, FirstLoginFilter
     * would block every endpoint except the change-password one. active is true
     * so that they can log in at all.
     */
    private User user(String first, String last, String email, String pwd, Role role) {
        return userRepo.findActiveByEmailWithRole(email).orElseGet(() ->
            userRepo.save(User.builder()
                .firstName(first).lastName(last).email(email)
                .passwordHash(pwd).active(true).firstLogin(false)
                .role(role).build()));
    }

    /**
     * Builds and saves one project from its characteristics and returns the
     * saved row, so the caller can hand that same object to the later steps.
     *
     * <p>Why the dates and the budget arrive as Strings: the fifteen calls
     * above then read as a table, and LocalDate.parse / new BigDecimal(String)
     * turn them back at the last moment. new BigDecimal("850000") is also the
     * correct way to build an exact amount - new BigDecimal(850000.0) would
     * start from a double and carry its approximation into the database.
     *
     * <p>currency is forced to TND and exchangeRateToTnd to 1: the whole
     * demonstration lives in a single currency, so no conversion can hide a
     * mistake in the figures on the screen.
     *
     * <p>archived is derived from the status instead of being passed in, so a
     * COMPLETED or CANCELLED project cannot be created by mistake while still
     * showing up in the active lists. The two stay separate fields on purpose:
     * "status" says where the project stands, "archived" says where it is
     * shown.
     */
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

    /**
     * Short name for new BigDecimal(String), used a few hundred times above.
     *
     * <p>Always built from a String and never from a double: new
     * BigDecimal(0.1) stores 0.1000000000000000055511151231257827, because that
     * is what a double really holds, while new BigDecimal("0.1") stores exactly
     * 0.1. On money that difference shows up as a few cents of drift once a few
     * hundred lines are added together.
     *
     * <p>static because it uses nothing from the object.
     */
    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    /**
     * Returns one element of the array, chosen from a number, wrapping round
     * when the number is larger than the array.
     *
     * <p>Math.abs guards against a negative index and the modulo against an
     * index past the end; either one would end the start-up on
     * ArrayIndexOutOfBoundsException.
     *
     * <p>It is used in step 5 to pick the monthly correction factor from the
     * month and the project number. Choosing it that way rather than with a
     * Random is what makes the generated dataset identical on every rebuild - a
     * Random would produce a different database every time, and a screenshot
     * taken for the report would stop matching the application.
     */
    private static String pickFromArray(String[] arr, int idx) {
        return arr[Math.abs(idx) % arr.length];
    }
}

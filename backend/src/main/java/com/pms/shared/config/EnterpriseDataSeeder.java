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
 * WHAT THIS FILE IS
 * A one-shot start-up script that fills an empty database with a large, realistic
 * "enterprise" dataset: 50 users, 30 projects and roughly 13 000 rows spread over the 21
 * business tables. It exists so the application can be shown and tested with data that
 * behaves like real data: a project list that really has several pages, filters that
 * really have something to filter, KPI curves that really have a shape, invoicing reports
 * that really contain money.
 *
 * WHERE IT SITS IN THE FLOW
 * Spring Boot start-up
 *   -&gt; Flyway runs the migrations V1..V28 and creates the empty tables;
 *   -&gt; then Spring calls every ApplicationRunner bean, in the order given by the
 *      Order annotation:
 *        DemoDataSeeder   (order 100) writes the small DEMO- dataset,
 *        THIS CLASS       (order 200) writes the large ENT- dataset,
 *        AgileDemoSeeder  (order 210) writes the sprints and backlog items of those
 *                         projects, which is why it MUST come after this class: it needs
 *                         the 30 projects created in section 3 to already exist,
 *        DataInitializer  (no order, so it runs last) resets the @pms.local demo logins.
 * It calls nothing except the 19 Spring Data repositories injected below; every save()
 * becomes one SQL INSERT. It is never reachable from an HTTP request, so it has no
 * controller, no DTO and no permission check: it runs as the application itself, before
 * anybody can log in. (DTO = Data Transfer Object, the small object a controller sends as
 * JSON; there is none here because nothing leaves this class.)
 *
 * WHY IT EXISTS
 * Delete it and the application still works, but every screen opens empty. A jury, or a
 * client during a demonstration, would see a login page followed by "no data": nothing to
 * paginate, no burn-down curve, no margin, no risk register. Typing 13 000 rows by hand
 * through the user interface is not realistic, and writing them as INSERT statements in a
 * Flyway migration would be worse: a migration is replayed on the production database too,
 * and it can never be removed once released. A seeder is ordinary Java that can be
 * skipped, guarded or deleted; a migration is permanent history.
 *
 * WHY THE NUMBERS ARE HARD-CODED AND NEVER RANDOM
 * Every rate, every over-run factor, every list of labels below is a fixed literal. There
 * is no Math.random() anywhere. Why: the dataset must be reproducible. With random values
 * the margin shown on project ENT-CRM-2026 would change at every restart, the screenshots
 * in the report would stop matching the running application, and a bug seen once could not
 * be reproduced. The variety that makes the data look alive comes from arithmetic on the
 * loop counters (i, m, r, ...) instead - see overrunFactor in section 5, which is what
 * makes some projects profitable and others loss-making.
 *
 * IDEMPOTENT - "running it twice does no more than running it once"
 * The first thing run() does is look for the sentinel project ENT-CRM-2026. If that code
 * is already stored, the method returns at once. Why this matters: an ApplicationRunner
 * fires on EVERY start of the application, and a developer restarts the backend dozens of
 * times a day. Without the guard, the second start would try to insert the same 50 e-mail
 * addresses again and crash on the unique index uk_users_email; and if it somehow did not
 * crash, the database would end up with 60 projects, 26 000 timesheet rows and KPI curves
 * drawn twice on top of each other.
 *
 * SECURITY - THE MOST IMPORTANT PARAGRAPH OF THIS FILE
 * Section 13 writes rows into lignes_di, the Devis Interne (DI = the company's internal
 * quote, its cost and margin sheet). Only the STRUCTURE is written: section, ordre,
 * profil contractuel and unite. Every money column - prixVenteUnitaire, coutUnitaireTcc,
 * fraisDivers, fraisGeneraux, coutImpots, tauxPourcentage - is deliberately left NULL.
 * This is the AMENDED DECISION 2026-07-05, also written at the top of the Flyway migration
 * V23__devis_interne.sql: the real daily costs and real margins of the company are
 * confidential, the company types them in itself after deployment, and they must never sit
 * in source code that is read, printed and archived by a jury. Keeping them out is also
 * what lets the DI rule hold everywhere else in the application: a computed amount is
 * always derived when the row is read, never stored in a column.
 *
 * ABOUT THE OTHER AMOUNTS IN THIS FILE
 * The budgets, daily rates and TCC rates below are invented figures attached to fictional
 * versions of well-known Tunisian organisations. They are plausible so the demonstration
 * is credible, but they are not the company's real figures - and the DI, the one place
 * where the real cost model would live, stays empty.
 *
 * CREDENTIALS
 * Every enterprise user logs in with its own e-mail and the shared password
 * Enterprise@ST2I2026!. That is acceptable only because this dataset is meant for a demo
 * or development database. See the "issues" note about the missing production guard.
 */
// Component makes Spring build one instance of this class at start-up and inject the
// repositories listed below. Why: without it the class is just a file nobody ever calls,
// and the database stays empty forever.
@Component
// Order(200) fixes WHEN this runner fires among all the other ApplicationRunner beans:
// after DemoDataSeeder, which is Order(100). Why the order matters: both seeders create
// users, and both read the RBAC roles created by Flyway. Running the enterprise seeder
// first would still work today, but the two datasets are meant to be layered - DEMO- first
// (small, hand-checked), ENT- on top (large, generated) - so that a developer who wants
// only the small dataset can disable this one and keep a coherent database.
@Order(200)
// RequiredArgsConstructor is Lombok: it writes, at compile time, the constructor that takes
// all the "private final" fields below. Spring then injects each repository through that
// constructor. Why constructor injection rather than @Autowired on each field: the fields
// can stay final, so nothing can replace a repository after start-up, and the class can be
// built in a unit test by simply passing fake repositories.
@RequiredArgsConstructor
// Slf4j is Lombok too: it adds the hidden field "log". Why: this seeder can run for a few
// seconds and writes thousands of rows, so it must say on the console whether it skipped,
// whether the roles were missing, or how much it inserted. Without it a developer facing an
// empty database has no way of knowing which of the three cases happened.
@Slf4j
// implements ApplicationRunner: Spring Boot calls run(args) once, after the application
// context is fully built and after Flyway has migrated the schema. Why this interface and
// not, say, a @PostConstruct method: @PostConstruct fires while beans are still being
// created, before Flyway is guaranteed to have finished, so the tables might not exist yet.
public class EnterpriseDataSeeder implements ApplicationRunner {

    // THE 19 REPOSITORIES + THE PASSWORD ENCODER
    // A Spring Data repository is an interface whose SQL Spring writes for us; calling
    // save(entity) issues one INSERT. There is one repository per table this seeder fills,
    // which is why the list is so long: the seeder touches the whole data model on purpose,
    // to prove that every module has something to display.
    // They are "final" so they are set once by the Lombok constructor and can never be
    // swapped afterwards. Why that matters here: this class writes 13 000 rows, and a
    // repository reassigned by mistake halfway through would write part of the dataset into
    // the wrong table.
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
    // PasswordEncoder is the BCrypt encoder declared in SecurityConfig. BCrypt is a hashing
    // function built to be slow on purpose: it turns a password into a one-way fingerprint
    // that cannot be turned back into the password.
    // Why the seeder needs it: the column users.password_hash must hold the same kind of
    // value that the login screen produces, otherwise nobody could log in with these 50
    // accounts. Without it, storing the plain text "Enterprise@ST2I2026!" would both break
    // every login (the check compares hashes) and publish the password to anyone who can
    // read one row of the users table.
    private final PasswordEncoder             pwdEncoder;

    // The sentinel: the project code this seeder looks for before doing anything. It is the
    // code of the FIRST project created below, so its presence means "this seeder has
    // already finished once". Why a project code and not, for example, a boolean in a
    // settings table: the code column already carries a unique index (uk_projects_code, on
    // rows where deleted = false), so the test is one cheap indexed lookup and cannot drift
    // out of step with the data it protects.
    private static final String SENTINEL_CODE = "ENT-CRM-2026";
    // The fake e-mail domain shared by the 50 generated accounts. Why a domain of its own:
    // "@ent.st2i.tn" makes an enterprise row recognisable at a glance in the users screen,
    // it can never collide with the DEMO- accounts (@pms.local) or with a real address, and
    // it makes the whole dataset removable with a single "delete where email like".
    private static final String ENT_DOMAIN    = "ent.st2i.tn";
    // The one password shared by all 50 accounts, hashed once in run() and reused. Why one
    // shared password: a demonstration means logging in as a director, then as a project
    // manager, then as a developer, within a few minutes; 50 different passwords would make
    // that impossible to present. The value is only ever safe because this dataset belongs
    // to a demo database - see the SECURITY note in the class header.
    private static final String ENT_PWD       = "Enterprise@ST2I2026!";

    // ─────────────────────────────────────────────────────────────────────────
    /**
     * WHAT IT DOES
     * Builds the whole enterprise dataset, in 13 numbered sections, in the only order the
     * foreign keys allow: users, then resources and yearly costs, then projects, then
     * everything that hangs off a project (teams, workload, KPI snapshots, missions,
     * invoicing, governance, and finally the empty DI structure). It returns nothing; its
     * result is the content of the database.
     * It returns early, doing nothing at all, in two cases: the data is already there, or
     * the RBAC roles it needs do not exist.
     *
     * WHY IT IS ONE LONG METHOD RATHER THAN THIRTEEN SMALL ONES
     * Each section needs the objects built by the sections before it: the mission loop needs
     * the project list AND the developer array AND the devIdx table that links them. Split
     * into private methods, all of that would have to travel as parameters or as fields, and
     * a field would be mutable state on a Spring singleton - a bean that exists once for the
     * whole application. Keeping everything as local variables of one method means the state
     * lives only while the method runs and disappears with it.
     * The price is length; the numbered banners below are what keeps it readable.
     *
     * @param args the command-line arguments Spring Boot passes to every ApplicationRunner.
     *             Unused here: the seeder has no options to read.
     */
    @Override
    // Transactional makes these thousands of INSERTs ONE single database unit of work:
    // either every row is committed, or none is.
    // Why it is needed: the sections depend on one another. Without it, each save() would
    // commit on its own, and a crash in section 8 (invoicing) would leave a database holding
    // 30 projects, their teams and their timesheets but no invoicing milestones at all. The
    // sentinel project ENT-CRM-2026 would already be committed, so the next restart would
    // see "already seeded" and skip - leaving that half-built dataset in place for good,
    // with screens that silently show nothing.
    // The cost, and it is a real one, is that the whole dataset sits in memory until the
    // commit; that is acceptable for a seeder that runs once on an empty database.
    @Transactional
    public void run(ApplicationArguments args) {
        // THE IDEMPOTENCY GUARD. "Does the sentinel project already exist, ignoring
        // soft-deleted rows?" If yes, this seeder has already run: stop here.
        // Why "AndDeletedFalse": PMS never really erases a row, it sets deleted = true. If
        // somebody soft-deletes the sentinel project, this test becomes false again and a
        // restart rebuilds the dataset - which is the wanted behaviour, since the unique
        // index uk_projects_code is also limited to rows where deleted = false, so the code
        // ENT-CRM-2026 is free again.
        // Without this guard: the second start of the backend would reach section 1 and try
        // to insert habib.zouari@ent.st2i.tn a second time, and PostgreSQL would reject it
        // on uk_users_email, so the application would fail to start.
        if (projectRepo.existsByCodeAndDeletedFalse(SENTINEL_CODE)) {
            log.info("EnterpriseDataSeeder — données enterprise déjà présentes, ignoré.");
            return;
        }

        // THE ROLE GUARD. Every user needs a role, and users.role_id is NOT NULL. The three
        // roles are created by the Flyway migrations, not here.
        // Note for the jury: these names are read ONLY to attach the right role row to each
        // generated user. The application never decides what somebody may do by testing a
        // role name - permissions are dynamic and checked with hasAuthority('X') on the
        // service methods. This seeder is the data side, not the authorization side.
        Role roleDir  = roleByName("DIRECTEUR");
        Role roleChef = roleByName("CHEF_PROJET");
        Role roleDev  = roleByName("DEVELOPPEUR");
        // Null means the roles table is empty, which happens in the test profile where the
        // schema is built without the RBAC seed migrations.
        // Why warn and return instead of throwing: a failure here would stop the whole
        // application from starting, and an integration test that does not care about demo
        // data would fail for the wrong reason. Continuing would be worse - the first
        // userRepo.save() would hit the NOT NULL constraint on role_id and abort anyway,
        // with a message that says nothing about the real cause.
        if (roleDir == null || roleChef == null || roleDev == null) {
            log.warn("EnterpriseDataSeeder — rôles RBAC introuvables, seeder ignoré.");
            return;
        }

        log.info("EnterpriseDataSeeder — génération du jeu de données enterprise (~13 000+ enreg.)...");
        // Hash the shared password ONCE, here, and reuse the same string for all 50 users.
        // Why this is not laziness: BCrypt is deliberately slow (the strength is 12 in
        // application-prod.yml, so about 4 000 rounds of key setup per call). Hashing inside
        // the user() helper would run it 50 times and add several seconds to every start-up
        // of the backend, for a value that is identical anyway.
        // Consequence to be honest about: because encode() is called once, the same salt is
        // used for all 50 accounts and the 50 rows store the exact same hash string. Real
        // accounts must never share a salt; here they already share the password, so nothing
        // extra is given away, and this dataset only lives on a demo database.
        String pwd = pwdEncoder.encode(ENT_PWD);

        // ── 1. Users: 8 Directeurs ─────────────────────────────────────────────
        // SECTION 1 - THE 50 PEOPLE. They come first because everything else points at a
        // user: a project has a director and a project manager, a team row has a member, a
        // timesheet row has an author and a validator.
        // The 8 / 12 / 30 split is a real company shape: few directors, a middle layer of
        // project managers, many developers. Why it matters for the demonstration: it is
        // what makes the screens realistic - a director filter returns 8 names, and the
        // workload screens have enough developers that pagination is actually exercised.
        // Each variable is kept (d1, cp1, devs[0]...) because the objects are reused later:
        // a saved entity carries the database id that the foreign keys below need.
        // The names are ordinary Tunisian first and last names, invented for the dataset.
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
        // The developers go into an ARRAY, not into 30 separate variables like the directors
        // and project managers above. Why: they are picked by number further down - the
        // devIdx table in section 4 says "project 3 gets developers 10, 11, 12, 13" - and
        // that only works if a developer has a stable position.
        // The trailing index comments (// 0, // 1, ...) are the key to reading devIdx: they
        // let you check by eye which person a number refers to. Without them, a line such as
        // {10,14,28,29,0} would be unreadable.
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
        // SECTION 2 - WHAT EACH PERSON COSTS.
        // A User is the login. A Resource is the cost sheet attached to that login: a daily
        // rate and a TCC rate. TCC ("Taux de Charge Complémentaire") is the extra-cost rate
        // the company adds on top of a salary to get the true cost of one day of work -
        // social charges, office, tooling. It is stored as a fraction, so 0.2800 means 28 %.
        // A TccAnnuel is the SAME pair of rates, but frozen for one calendar year.
        //
        // WHY TWO TABLES INSTEAD OF ONE
        // Resource holds today's rate, used when planning ahead. TccAnnuel holds the rate
        // that applied in a given year, used when recomputing an old project. Without the
        // yearly rows, raising a developer's rate in 2026 would silently rewrite the cost of
        // work he did in 2024, and a finished project's margin would change on its own.
        //
        // These literals are invented example figures, not the company's real rates; the
        // real cost model lives in the DI, which section 13 deliberately leaves empty.
        // The rates are written as STRINGS, never as 820.0. Why: they end up in BigDecimal,
        // the exact decimal type used for every amount in PMS. new BigDecimal("0.2800") is
        // exactly 0.2800; new BigDecimal(0.2800) built from a double is
        // 0.2800000000000000266..., and that tiny error multiplied over thousands of
        // timesheet days is how a total ends at 12 345.67 on one screen and 12 345.68 on
        // another.
        // The intended bands are Directors 720-860 TND/day, project managers 560-680 and
        // developers 420-560; the literals actually used below sit inside those bands
        // (780-850, 610-670, 470-530). Why the three levels must not overlap: the whole
        // point of the dataset is that a project staffed with senior people costs more, so
        // the margin screens show different figures from one project to another.
        // The TCC arrays follow the same idea - a director carries more overhead (about
        // 27-29 %) than a developer (about 24 %).
        // Each array is positional: dirRates[0] belongs to d1, chefRates[0] to cp1,
        // devRates[0] to devs[0]. The three arrays are then glued together further down in
        // exactly that order, so a value inserted in the middle of one of them would silently
        // give every later person the wrong cost.
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

        // Put the 50 people in ONE list, in the fixed order directors, then project
        // managers, then developers. Why: the rate arrays are about to be glued together in
        // that same order, and position i of the list must match position i of the rates.
        // List.of(...) builds a small read-only list; addAll copies its contents into the
        // growable ArrayList. The developers are added with a loop because devs is a Java
        // array, which addAll cannot take directly.
        List<User> allEntUsers = new ArrayList<>();
        allEntUsers.addAll(List.of(d1, d2, d3, d4, d5, d6, d7, d8));
        allEntUsers.addAll(List.of(cp1, cp2, cp3, cp4, cp5, cp6, cp7, cp8, cp9, cp10, cp11, cp12));
        for (User dv : devs) allEntUsers.add(dv);

        // Glue the three rate arrays into one 50-slot array, and the same for the TCC rates.
        // System.arraycopy(source, fromIndex, target, atIndex, howMany) is the plain Java way
        // to copy a block of an array. The three blocks are laid out as 0..7 (8 directors),
        // 8..19 (12 project managers) and 20..49 (30 developers).
        // Why bother instead of writing one array of 50 literals: the three professions have
        // clearly separate bands, and keeping them apart above is what makes an error
        // visible. The danger is that the block sizes here must match the number of people
        // added in the same order just above - if a 13th project manager were added without
        // changing the "8, 12" here, every developer would be paid a project manager's rate
        // and the dataset would look plausible while being wrong.
        String[] allRates = new String[50];
        String[] allTccs  = new String[50];
        System.arraycopy(dirRates,  0, allRates, 0,  8);
        System.arraycopy(chefRates, 0, allRates, 8,  12);
        System.arraycopy(devRates,  0, allRates, 20, 30);
        System.arraycopy(dirTccs,   0, allTccs,  0,  8);
        System.arraycopy(chefTccs,  0, allTccs,  8,  12);
        System.arraycopy(devTccs,   0, allTccs,  20, 30);

        // One Resource per person, then three TccAnnuel rows per Resource: 50 + 150 rows.
        // Honest note: entResources is filled but never read again - no later section needs
        // the resources, they are found through their user. It is dead weight, kept here
        // only because removing it is a code change and this pass adds comments only.
        List<Resource> entResources = new ArrayList<>();
        for (int i = 0; i < allEntUsers.size(); i++) {
            // bd() wraps new BigDecimal(String): exact decimal arithmetic, see section 2's
            // note above on why the rates are strings.
            BigDecimal rate = bd(allRates[i]);
            BigDecimal tcc  = bd(allTccs[i]);
            // save() returns the entity as Hibernate stored it, which now carries its
            // generated database id. That returned object is what must be used afterwards:
            // the TccAnnuel rows below need a Resource that already has an id, otherwise the
            // foreign key resource_id could not be written.
            Resource res = resourceRepo.save(Resource.builder()
                .user(allEntUsers.get(i))
                .dailyRate(rate)
                .tccRate(tcc)
                // staffing_start is NOT NULL in the resources table. 1 January 2023 is
                // before the earliest project start in this dataset, so no timesheet row can
                // ever fall outside a person's staffing window.
                .staffingStart(LocalDate.of(2023, 1, 1))
                // staffingEnd is left null on purpose: null means "still staffed today",
                // which is what a demo dataset wants for all 50 people.
                .build());
            entResources.add(res);
            // TCC annuels pour 2024, 2025, 2026
            // Three years of history, built from the current rate rather than typed again.
            // yearMults bends the daily rate: 2024 was 3 % cheaper, 2025 is the reference,
            // 2026 is 4 % dearer. yearAdj shifts the TCC by a whole number of points:
            // -1 point in 2024, unchanged in 2025, +1.2 points in 2026.
            // Why a rising curve: it makes the yearly comparison screens show a real trend,
            // and it proves the "frozen per year" design - recomputing a 2024 project uses
            // the 2024 numbers, not today's.
            String[] yearMults = {"0.97", "1.00", "1.04"};
            String[] yearAdj   = {"-0.0100", "0.0000", "0.0120"};
            for (int y = 0; y < 3; y++) {
                int annee = 2024 + y;
                // setScale(2, HALF_UP) forces exactly two decimals, rounding 0.005 upwards.
                // Why: the column daily_rate is NUMERIC(10,2). Handing Hibernate a value with
                // four decimals would either be rounded by PostgreSQL behind our back or
                // rejected, depending on the driver - so the rounding is done here, once,
                // where it can be seen.
                BigDecimal adjRate = rate.multiply(bd(yearMults[y])).setScale(2, RoundingMode.HALF_UP);
                // Same idea with four decimals, because tcc_rate is NUMERIC(5,4): at most one
                // digit before the point and four after, so 0.2680 fits and 1.26800 would not.
                BigDecimal adjTcc  = tcc.add(bd(yearAdj[y])).setScale(4, RoundingMode.HALF_UP);
                // The unique index uk_tcc_annuel_resource_annee covers (resource_id, annee)
                // on live rows. The loop writes 2024, 2025 and 2026 once each per resource,
                // so it can never collide - and if this section were ever run twice on the
                // same resource, PostgreSQL would stop it rather than store two different
                // costs for the same person and the same year.
                tccRepo.save(TccAnnuel.builder()
                    .resource(res).annee(annee)
                    .dailyRate(adjRate).tccRate(adjTcc)
                    .build());
            }
        }

        // ── 3. Projects (30) ──────────────────────────────────────────────────
        // SECTION 3 - THE 30 PROJECTS, the spine of the dataset. Everything after this
        // section hangs off one of these rows.
        //
        // WHY THE STATUSES ARE SPLIT 12 / 6 / 4 / 3 / 5
        // The five groups below are the five values of ProjectStatus: ACTIVE, COMPLETED,
        // ON_HOLD, CANCELLED, DRAFT. Every one of them is represented on purpose, because
        // the later sections behave differently for each - a DRAFT project gets no timesheet
        // and no KPI snapshot, a CANCELLED one gets no mission, only ACTIVE and COMPLETED
        // get a DI structure. Seeding only ACTIVE projects would leave the status filter,
        // the archive screen and half of those branches untested.
        //
        // WHY EACH ARGUMENT LIST LOOKS THE SAME
        // They all call the private proj(...) helper at the bottom of the file, whose 20
        // parameters are, in order: code, name, description, status, director, project
        // manager, client, funder, start, end, budget, business model, engagement type,
        // sold net margin, contract id, currency, exchange rate to TND, licence and
        // subcontracting budget, sold workload in days, warranty workload in days.
        // Twenty positional arguments is a lot; the vertical alignment below is what makes a
        // column readable down the 30 lines, so a wrong currency or a wrong status stands out.
        //
        // WHAT THE ARGUMENTS MEAN WHERE IT IS NOT OBVIOUS
        //   funder      - the institution that pays when the client does not (World Bank,
        //                 EU, AfDB). null when the client funds the project itself.
        //   SEUL        - the company delivers alone; GROUPEMENT - it delivers inside a
        //                 consortium of several companies.
        //   FORFAIT     - fixed price: the company carries the risk of an over-run;
        //                 REGIE - time and materials: the client pays the days actually
        //                 worked. That choice is what makes an over-run a loss or not.
        //   marge       - the net margin sold, as a fraction: 0.38 means 38 % was promised
        //                 at signature. Lower on the very large public contracts (0.28-0.30),
        //                 higher on short consulting jobs (0.42-0.48) - which is how tenders
        //                 really work.
        //   currency    - EUR or TND, with the matching exchange rate to TND next to it.
        //                 Why both: the contract is signed in its own currency, but every
        //                 internal amount is compared in TND. EUR projects use 3.30.
        //   soldWl      - the workload sold, in days; warrantyWl is the part of it reserved
        //                 for the free-support period after delivery, and it is always
        //                 exactly 5 % of soldWl in this dataset (1250.00 -> 62.50).
        //
        // The clients are real Tunisian organisations but the contracts, budgets, dates and
        // contract identifiers are entirely invented for this demonstration.
        // ── ACTIVE (12) ───────────────────────────────────────────────────────
        // ACTIVE = work in progress. These 12 are the ones the dashboards are really about:
        // they get timesheets up to today, a growing KPI curve, missions, invoicing
        // milestones and an empty DI structure.
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
        // COMPLETED = delivered and closed. Their dates are in the past, so their timesheets
        // and KPI snapshots run to the end and their deliverables are all VALIDE or LIVRE.
        // The proj() helper also sets archived = true for them, which is what removes them
        // from the default project list and puts them behind the "archived" filter.
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
        // ON_HOLD = suspended, not cancelled: the contract still exists but the work stopped.
        // Section 6 caps their progress at 35 %, which is what makes a paused project look
        // paused on the KPI screen - a flat curve that stops climbing instead of a curve
        // that simply ends.
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
        // CANCELLED = stopped for good, before the end. Each description says why, because
        // that is the first question a jury asks when it sees a cancelled project on screen.
        // Section 6 caps their progress at 20 %, and they receive no mission, no risk, no
        // stakeholder and no DI - a dead project must not keep generating data.
        // They are archived too, like the COMPLETED ones.
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
        // DRAFT = a project being prepared, not yet signed. Notice the pattern in the
        // arguments: client, funder, contractId, licence budget, sold workload and warranty
        // workload are all null, and only the currency and the exchange rate are filled.
        // Why on purpose: a project that has not been signed HAS no client contract and no
        // sold workload. Filling them would hide a real question - the screens must cope
        // with missing values, and the later sections must skip these rows. That is exactly
        // what the "if (status == DRAFT) continue;" line does at the top of sections 5, 6, 8,
        // 9, 10, 11 and 12.
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

        // ONE list holding the 30 saved projects, in the order p01..p30. This list is the
        // index used by every section that follows: devIdx[i], projDays[i] and
        // overrunFactor[i] all describe allProjs.get(i).
        // List.of(...) makes it read-only. Why that is the right choice here: an accidental
        // add or remove later would shift every index by one, and each project would silently
        // inherit another project's team and another project's over-run factor. An immutable
        // list turns that mistake into an immediate UnsupportedOperationException instead of
        // a dataset that looks fine and is wrong.
        List<Project> allProjs = List.of(
            p01, p02, p03, p04, p05, p06, p07, p08, p09, p10,
            p11, p12, p13, p14, p15, p16, p17, p18, p19, p20,
            p21, p22, p23, p24, p25, p26, p27, p28, p29, p30
        );

        // ── 4. Team assignments ───────────────────────────────────────────────
        // SECTION 4 - WHO WORKS ON WHAT. A TeamAssignment row is the link between a project
        // and a person, with the job title that person holds on that project.
        //
        // WHY THIS SECTION IS NOT DECORATION
        // Team membership is what the project-scope rule reads. ADR-021 says that on a URL
        // like /api/projects/{id}/**, ProjectScopeInterceptor checks BOTH the permission AND
        // whether the caller belongs to that project: having the permission is not enough.
        // Without these rows, a seeded developer who logs in would hold his permissions and
        // still be refused on every project page, and the whole dataset would look broken.
        //
        // Each project gets its PM + 3-5 developers
        // devIdx is a table of tables: row i lists the positions in the devs array of the
        // developers who work on allProjs.get(i). The trailing comments (// p01, // p02...)
        // are the only way to keep the 30 rows aligned with the 30 projects.
        // Two rules hold inside every row, and both matter:
        //   - the numbers inside one row are all different, because the unique index
        //     uk_ta_project_user_active covers (project_id, user_id) on live rows: the same
        //     person twice on the same project would be rejected by PostgreSQL;
        //   - the same number appears in several rows on purpose, so a developer works on
        //     two or three projects at once. Without that overlap the workload screens would
        //     never show a person over-booked, and the "my projects" list would always hold
        //     exactly one line.
        // Team sizes shrink towards the end (3 for p22, 1 for p24, 2 for the drafts): those
        // are the cancelled and not-yet-started projects, which really do have small teams.
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
        // The job titles given to team members, in the order they are handed out.
        // IMPORTANT: this is a free-text label stored in team_assignments.role_in_team. It is
        // NOT the RBAC role and it grants nothing at all. What a user may do comes from the
        // permissions of his Role entity, checked with hasAuthority on the service methods;
        // this string only tells a human reading the team tab what the person does.
        // Naming it "Architecte Solution" instead of "DEVELOPPEUR" is deliberate: it makes
        // the team screen look like a real staffing sheet.
        String[] roleLabels = {
            "Développeur Backend Senior", "Développeur Frontend", "Développeur Full-Stack",
            "Analyste Fonctionnel", "Architecte Solution", "Testeur QA Senior",
            "Consultant Fonctionnel", "Ingénieur DevOps", "Lead Developer", "Scrum Master"
        };

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj   = allProjs.get(i);
            User    chef  = prj.getChefProjet();
            // The project manager is added to his own team, as a separate row.
            // Why he is not simply "the chef_projet column of the project": the scope rule of
            // ADR-021 looks at team membership. If the manager were only named in the project
            // row, he would be refused on the very project he runs.
            // The null test is defensive - every project above does have a manager, but
            // project.chef_projet is nullable in the schema, and calling getId() on null
            // during start-up would stop the whole application.
            if (chef != null) {
                teamRepo.save(TeamAssignment.builder()
                    .project(prj).user(chef).roleInTeam("Chef de Projet")
                    // The assignment covers the whole project, start to end. Why copy the
                    // project dates instead of leaving them null: the workload screens filter
                    // team rows by period, and a row with no dates would either be excluded
                    // or counted in every month.
                    .startDate(prj.getStartDate()).endDate(prj.getEndDate())
                    .build());
            }
            int[] members = devIdx[i];
            for (int j = 0; j < members.length; j++) {
                // members[j] is a position in the devs array, not a database id.
                User dev = devs[members[j]];
                teamRepo.save(TeamAssignment.builder()
                    .project(prj).user(dev)
                    // "j % roleLabels.length" wraps around the 10 titles so the j-th member
                    // always gets a title even if a team ever grew past 10 people.
                    // Why modulo rather than a fixed title per person: within one project the
                    // titles then come out all different (teams are at most 5 here), which
                    // reads like a real team instead of five identical "Développeur" lines.
                    .roleInTeam(roleLabels[j % roleLabels.length])
                    .startDate(prj.getStartDate()).endDate(prj.getEndDate())
                    .build());
            }
        }

        // ── 5. PlanCharge + ChargeReelle ──────────────────────────────────────
        // SECTION 5 - THE TIMESHEETS, by far the biggest section: it alone produces most of
        // the 13 000 rows.
        // PlanCharge = the days PLANNED for one person, on one project, for one month.
        // ChargeReelle = the days ACTUALLY worked, same three keys, plus who validated them.
        // Why two tables rather than one row with two columns: a plan exists for months that
        // have not happened yet, and an actual exists only once the month is over. Keeping
        // them apart is what lets the KPI screens compare "planned" against "done" and show
        // a gap - which is the whole point of the indicator screens.
        //
        // Base JH/month per project — drives profitability scenarios
        // JH = "jour-homme", one person working one day. Each figure is the number of days a
        // single team member is planned for, per month, on that project. Bigger projects get
        // bigger numbers (26.0 for p10, the 4.2 M education programme; 11.0 for p24, the
        // three-month audit that was cancelled).
        // A month has at most 31 days, and the database enforces it: plan_charges carries
        // CHECK (planned_days > 0 AND planned_days <= 31). Every figure here stays well under
        // that ceiling so the variance added below cannot push a row over it.
        double[] projDays = {
            14.0, 18.0, 22.0, 16.0, 13.0, 20.0, 24.0, 17.0, 15.0, 26.0,
            19.0, 12.0, 15.0, 20.0, 13.0, 22.0, 16.0, 24.0, 18.0, 14.0,
            21.0, 15.0, 19.0, 11.0, 17.0, 14.0, 12.0, 18.0, 16.0, 20.0
        };
        // Variation multipliers applied per project type: drives profit/loss
        // THE MOST IMPORTANT ARRAY OF THE WHOLE FILE. It says, for each project, how the
        // real consumption compares with the plan:
        //   above 1.00 - the project burns more than planned. 1.35 on p10 means +35 %.
        //   below 1.00 - the project burns less than planned. 0.88 on p12 means -12 %.
        //   exactly 1.00 - used for the DRAFT projects, which never reach this loop anyway.
        // It is used twice: here, to bend the real days away from the planned days, and
        // again in section 6, where the same factor bends the consumed budget away from the
        // planned budget. Using ONE array in both places is what keeps the dataset coherent:
        // a project that over-burns days also over-burns money, so the timesheet screen and
        // the margin screen tell the same story.
        // Without this array every project would land exactly on plan, every margin would be
        // the margin sold, and the alert screens - "project in over-run" - would be empty
        // and impossible to demonstrate.
        double[] overrunFactor = {
            1.15, 0.95, 1.22, 0.98, 0.91, 0.97, 1.28, 1.05, 0.93, 1.35,
            1.02, 0.88, 0.96, 1.18, 0.93, 1.08, 0.97, 1.24, 1.12, 1.00,
            1.06, 0.95, 1.20, 1.14, 1.08, 1.00, 1.00, 1.00, 1.00, 1.00
        };

        // "Today", read ONCE and reused by sections 5, 6 and 10. It is the line that
        // separates the past from the future in the whole dataset: months before refDate get
        // real timesheets and KPI snapshots, months after it get only a plan.
        // Why read once instead of calling LocalDate.now() at each use: the seeder can run
        // across midnight, and two different "todays" inside the same run would produce a
        // project whose last timesheet is after its last KPI snapshot.
        // The practical consequence is that this dataset ages: run on a later date, it
        // produces more real timesheets, exactly as a live database would.
        LocalDate refDate = LocalDate.now();

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // DRAFT projects have not started and CANCELLED projects were stopped, so
            // neither has any timesheet. Without this line, a draft project would show
            // planned days for a team that was never staffed, and the "workload to come"
            // total on the capacity screen would count work nobody will ever do.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;

            // Turn the list of positions for this project into the actual User objects.
            User[] members = new User[devIdx[i].length];
            for (int j = 0; j < devIdx[i].length; j++) members[j] = devs[devIdx[i][j]];
            // Guard against an empty team: with no members the inner loop would write
            // nothing anyway, but skipping early avoids walking every month of the project
            // for nothing.
            if (members.length == 0) continue;

            double baseDays     = projDays[i];
            double overrun      = overrunFactor[i];
            // Who signs off the timesheets. In PMS a ChargeReelle is submitted by the person
            // and then validated by the project manager, so validated_by must be somebody.
            // The fallback to d1 covers the case where a project has no manager - it cannot
            // happen with the 30 projects above, but validated_by is written unconditionally
            // just below, and passing null there would store a validated row with no
            // validator, which is exactly the kind of half-state the workflow forbids.
            User   validator    = prj.getChefProjet() != null ? prj.getChefProjet() : d1;
            // Fallback dates for the same reason: start_date and end_date are nullable in the
            // schema. Walking a month cursor from a null start would throw a
            // NullPointerException and stop the application from starting.
            LocalDate start     = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate end       = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2026, 12, 31);
            // withDayOfMonth(1) snaps the cursor to the first day of the month.
            // Why it is required: plan_charges.period and charges_reelles.period both mean
            // "the month", and the V7 migration says so in a comment - "toujours le 1er du
            // mois". The unique indexes uk_pc_active and uk_cr_active are built on
            // (project_id, user_id, period), so a project starting on the 15th would produce
            // a period of the 15th, which would NOT collide with a row for the 1st: the same
            // person could then hold two timesheets for the same month and every total would
            // be counted twice.
            LocalDate cursor    = start.withDayOfMonth(1);

            // Walk month by month from the project start to its end, inclusive.
            // "!cursor.isAfter(end)" rather than "cursor.isBefore(end)" so the last month is
            // included: a project ending 2027-03-31 must have a March 2027 line.
            while (!cursor.isAfter(end)) {
                // A small wobble around the base figure, so a project is not a flat line
                // month after month. The pattern repeats every four months: +2.0, -1.5, +0.5,
                // -0.5 days. It is driven by the calendar month number, so it is the same on
                // every run - see the "no random" rule in the class header.
                double variance = (cursor.getMonthValue() % 4 == 0) ? 2.0
                                : (cursor.getMonthValue() % 4 == 1) ? -1.5
                                : (cursor.getMonthValue() % 4 == 2) ? 0.5 : -0.5;
                // Math.max(1.0, ...) is a floor, not decoration: plan_charges has
                // CHECK (planned_days > 0 AND planned_days <= 31). The smallest base figure is
                // 11.0 and the worst variance is -1.5, so the floor is never reached today -
                // it is there so that lowering a figure in projDays to 1.0 later cannot make
                // the whole application fail to start on a constraint violation.
                // String.format with Locale.US forces a DOT as the decimal separator. Why it
                // matters: on a French or Tunisian machine the default locale formats 14.5 as
                // "14,5", and new BigDecimal("14,5") throws NumberFormatException. Without
                // Locale.US this seeder works on the developer's machine and crashes on
                // another one - the classic bug that is impossible to reproduce.
                BigDecimal planned = bd(String.format(Locale.US, "%.2f", Math.max(1.0, baseDays + variance)));

                for (User dev : members) {
                    // One planned row per member and per month, for the WHOLE project, future
                    // months included. That is what fills the capacity screens.
                    planRepo.save(PlanCharge.builder()
                        .project(prj).user(dev).period(cursor).plannedDays(planned)
                        .build());

                    // Real days exist only for months that are already over. Without this
                    // test the dataset would contain timesheets for 2029, and every
                    // "consumed so far" total would be meaningless.
                    if (cursor.isBefore(refDate)) {
                        // The real consumption = the project's over-run factor, times a
                        // month-to-month ripple between 0.88 and 1.20.
                        // How the ripple is built: (month * 7 + projectIndex * 3) % 17 gives a
                        // whole number from 0 to 16, and 0.02 turns it into 0.00 .. 0.32, added
                        // to 0.88. The 17 is a prime number, which makes the sequence take a
                        // long time to repeat instead of falling into a short visible cycle.
                        // Mixing in the project index i means two projects in the same month
                        // do not ripple the same way, so the curves on a multi-project chart
                        // are not parallel copies of one another.
                        double actualMult = overrun * (0.88 + (Math.abs((cursor.getMonthValue() * 7 + i * 3) % 17) * 0.02));
                        BigDecimal actual = planned.multiply(bd(String.format(Locale.US, "%.4f", actualMult)))
                            // Two decimals, because actual_days is NUMERIC(5,2).
                            .setScale(2, RoundingMode.HALF_UP);
                        // The two clamps below exist for the database constraint
                        // CHECK (actual_days >= 0 AND actual_days <= 31) on charges_reelles.
                        // Ceiling: 26.0 planned days times a 1.35 over-run times the 1.20 top
                        // of the ripple gives about 42 days, which PostgreSQL would refuse -
                        // the INSERT fails, the whole transaction rolls back and the
                        // application starts with an empty database. This line is what stops
                        // that.
                        if (actual.compareTo(bd("31.00")) > 0) actual = bd("31.00");
                        // Floor: keeps the value strictly positive, so a timesheet row always
                        // means real work. compareTo is used instead of equals because
                        // BigDecimal.equals also compares the number of decimals, so
                        // 0.00 and 0.0 are "not equal" to it while compareTo sees them as the
                        // same number.
                        if (actual.compareTo(BigDecimal.ZERO) <= 0) actual = bd("0.50");
                        chargeRepo.save(ChargeReelle.builder()
                            .project(prj).user(dev).period(cursor).actualDays(actual)
                            // The submit-then-validate trail: declared on the 23rd of the
                            // month, validated four days later. atStartOfDay() turns the date
                            // into a timestamp at 00:00, because the columns are TIMESTAMP.
                            // Why fill them at all: a ChargeReelle with no validated_at is a
                            // row still waiting for approval. Leaving all 8 000 rows pending
                            // would make every consumed total read as zero on the screens that
                            // only count validated work.
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
        // SECTION 6 - THE MONTHLY PHOTOGRAPH OF EACH PROJECT.
        // A SnapshotKpi is one row per project and per month holding what the indicators
        // showed on that date: budget planned and consumed, estimate at completion, margin,
        // progress, produced revenue, invoiced amount, and a free-text highlight.
        //
        // WHY THESE NUMBERS ARE STORED AND NOT RECOMPUTED
        // This is the exception that proves the rule. Everywhere else in PMS - and in the DI
        // above all - a computed amount is derived when it is read, never stored. Here the
        // point is the opposite: a snapshot is history. "What did the margin look like in
        // March?" cannot be answered by recomputing today, because the timesheets of March
        // have since been corrected and the budget has since been amended. Freezing the
        // figures is the only way a curve over time stays stable.
        //
        // Short glossary for the fields written below:
        //   EV (earned value) - the share of the work really produced, in per cent.
        //   EAC (estimate at completion) - what the project will have cost by the end, seen
        //        from today.
        //   RAF ("reste a faire") - the work still left to do, in days.
        //   CA de production - the revenue matching the work produced.
        //   FAE ("facture a etablir") - revenue produced but not invoiced yet.
        //
        // The free-text highlights, reused in rotation over the 30 projects. Why they are
        // here at all: the KPI screen shows a comment column, and a column of empty cells
        // makes a demonstration look unfinished. They are ordinary project-review sentences
        // (steering committee, sprint review, scope drift, user acceptance) and they are
        // fictional.
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
            // DRAFT projects are skipped - nothing has been produced, so there is nothing to
            // photograph. CANCELLED projects are NOT skipped here (unlike in section 5):
            // a cancelled project did run for a while and its history must stay visible,
            // which is exactly what the 20 % ceiling below expresses.
            if (prj.getStatus() == ProjectStatus.DRAFT) continue;

            LocalDate start = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate end   = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2026, 12, 31);
            // Every amount below is a share of the initial budget, so a budget is required.
            // The 500 000 fallback only protects against a null column; all 30 projects above
            // do have one. Without it, budget.multiply(...) on null would stop start-up.
            BigDecimal budget = prj.getInitialBudget() != null ? prj.getInitialBudget() : bd("500000");

            LocalDate cursor = start.withDayOfMonth(1);
            // snap counts the snapshots of THIS project, starting at 0. It is what makes the
            // curve climb: it is the "how many months in" number, independent of the calendar.
            int snap = 0;
            // The ceiling each project's progress may reach. This single switch is what makes
            // the four statuses look different on a chart:
            //   COMPLETED -> 100 %: the curve reaches the top and stops.
            //   ON_HOLD   ->  35 %: the curve climbs, then flattens where the work stopped.
            //   CANCELLED ->  20 %: a short curve that dies early.
            //   default (ACTIVE) -> 85 %: still climbing, not finished - which is the honest
            //                       picture for work in progress.
            // This is a switch EXPRESSION (the arrow form, Java 14+): it returns a value
            // instead of assigning inside each branch, so no "break" can be forgotten and
            // maxSnapPct is guaranteed to be set exactly once.
            double maxSnapPct = switch (prj.getStatus()) {
                case COMPLETED -> 100.0;
                case ON_HOLD   -> 35.0;
                case CANCELLED -> 20.0;
                default        -> 85.0;
            };

            // Two conditions at once: stay inside the project, and stay in the past.
            // The second one is what makes a snapshot history rather than a forecast - there
            // is no such thing as "what the indicators showed next March".
            while (!cursor.isAfter(end) && cursor.isBefore(refDate)) {
                // Progress: 3.5 % at the first snapshot, then +7.5 points per month, capped by
                // the status ceiling. Straight-line growth is deliberate - it draws a clean,
                // readable curve on the dashboard instead of a noisy one.
                double rawPct  = Math.min(maxSnapPct, snap * 7.5 + 3.5);
                // Earned value wobbles around the consumption: slightly ahead every third
                // month, slightly behind otherwise. Why not equal to rawPct: if consumption
                // and produced value were always identical, the gap the indicator screens
                // exist to show would always be zero.
                double evPct   = Math.min(100.0, rawPct + (snap % 3 == 0 ? 1.5 : -1.0));
                // Delivery runs 2 points ahead of earned value: things are handed over before
                // they are formally recognised as produced.
                // Both are capped at 100.0 because these are percentages; a chart axis with a
                // 103 % point on it looks like a bug even when the underlying data is fine.
                double delPct  = Math.min(100.0, evPct + 2.0);
                // The SAME over-run factor as section 5 - this is what ties the timesheets and
                // the money together.
                double factor  = overrunFactor[i];

                BigDecimal evPctBd  = bd(String.format(Locale.US, "%.2f", evPct));
                // Planned budget at this date = total budget times progress.
                // The percentage is turned into a 4-decimal string first, so the whole
                // computation stays in exact BigDecimal arithmetic instead of drifting through
                // a double. Locale.US, again, for the decimal dot.
                BigDecimal budPlan  = budget.multiply(bd(String.format(Locale.US, "%.4f", rawPct / 100.0))).setScale(2, RoundingMode.HALF_UP);
                // Consumed budget = planned budget bent by the over-run factor. Above 1.00 the
                // project has spent more than it should have at this point.
                BigDecimal budConso = budPlan.multiply(bd(String.format(Locale.US, "%.4f", factor))).setScale(2, RoundingMode.HALF_UP);
                // Margin = planned minus consumed. It is NEGATIVE whenever factor > 1.00, and
                // that is the point: the dataset must contain loss-making projects, otherwise
                // the red alerts on the dashboard can never be shown.
                BigDecimal margeK   = budPlan.subtract(budConso);
                // EAC: the whole budget bent by the same factor - "if it keeps going like
                // this, this is what it will have cost".
                BigDecimal eac      = budget.multiply(bd(String.format(Locale.US, "%.4f", factor))).setScale(2, RoundingMode.HALF_UP);
                // Produced revenue follows EARNED VALUE, not consumption: a company earns on
                // what it has produced, not on what it has spent. That distinction is the
                // whole reason both percentages exist.
                BigDecimal caProd   = budget.multiply(bd(String.format(Locale.US, "%.4f", evPct / 100.0))).setScale(2, RoundingMode.HALF_UP);
                // 65 % of the produced revenue has been invoiced. The remaining 35 % becomes
                // the FAE just below. A fixed ratio keeps the two numbers consistent on every
                // row; invoicing always lags production in real life.
                BigDecimal factPct  = caProd.multiply(bd("0.65")).setScale(2, RoundingMode.HALF_UP);

                kpiRepo.save(SnapshotKpi.builder()
                    // The unique index uk_kpi_project_date covers (project_id, snapshot_date)
                    // on live rows, and the cursor advances one month at a time from the first
                    // of the month, so one project can never get two snapshots for one date.
                    .project(prj).snapshotDate(cursor)
                    .budgetPlanifie(budPlan).budgetConsome(budConso).eac(eac).marge(margeK)
                    // Consumption rate stored as a fraction (0.4250), not as 42.50. Four
                    // decimals, matching the column, so 42.5 % is exact rather than rounded.
                    .tauxConsommation(bd(String.format(Locale.US, "%.4f", rawPct / 100.0)))
                    .evPct(evPctBd)
                    .deliveryPct(bd(String.format(Locale.US, "%.2f", delPct)))
                    // Days consumed and days left, derived from the same progress figure with
                    // two fixed coefficients (3.5 and 3.2). They are not meant to match the
                    // timesheets of section 5 row by row - a snapshot is a summary typed by a
                    // project manager, and 3.2 being slightly under 3.5 is what makes the
                    // remaining work shrink a little faster than the consumed work grows.
                    .consommeJh(bd(String.format(Locale.US, "%.2f", rawPct * 3.5)))
                    .rafJh(bd(String.format(Locale.US, "%.2f", (100.0 - rawPct) * 3.2)))
                    // The drift in days: zero when factor is exactly 1.00, positive when the
                    // project over-burns, negative when it under-burns. Written as
                    // (factor - 1.0) precisely so that an on-plan project shows 0.00 and not
                    // some small residue.
                    .deriveJh(bd(String.format(Locale.US, "%.2f", (factor - 1.0) * rawPct * 2.0)))
                    .caProduction(caProd).totalFacture(factPct)
                    // FAE = produced minus invoiced, so the three figures always add up. It is
                    // computed by subtraction rather than as "35 % of caProd" for that reason:
                    // two independent roundings could leave a one-cent gap.
                    .fae(caProd.subtract(factPct))
                    .margeActuelle(margeK)
                    // Margin as a fraction of the planned budget. The
                    // "budPlan == 0 ? 1 : budPlan" test is a DIVISION-BY-ZERO guard. With the
                    // 30 projects above it never fires, because progress starts at 3.5 % of a
                    // budget of at least 150 000. It matters because dividing a double by 0.0
                    // does NOT throw: it quietly yields NaN or Infinity, String.format then
                    // writes the text "NaN", and new BigDecimal("NaN") throws a
                    // NumberFormatException that rolls back the entire seeding transaction and
                    // leaves the application starting on an empty database. Substituting 1
                    // makes the result 0.0000 instead - the truthful answer when nothing has
                    // been planned yet - so adding a project with a zero budget later cannot
                    // break start-up.
                    .margeActuellePct(bd(String.format(Locale.US, "%.4f", margeK.doubleValue() / (budPlan.doubleValue() == 0 ? 1 : budPlan.doubleValue()))))
                    // The forecast end date moves around the contractual end: on time, then 15
                    // days late, then 7 days early, repeating. Why make it move: a forecast
                    // that never changes is not a forecast, and the deviation column on the
                    // dashboard would be empty on every row.
                    .dateFinEstimee(end.plusDays(snap % 3 == 0 ? 0 : snap % 3 == 1 ? 15 : -7))
                    // (snap + i) shifts where each project starts in the list of highlights, so
                    // two projects looked at side by side do not show the same sentence in the
                    // same month. The modulo simply wraps back to the beginning of the array.
                    .faitsMarquants(faitsMarquants[(snap + i) % faitsMarquants.length])
                    .build());
                snap++;
                cursor = cursor.plusMonths(1);
            }
        }

        // ── 7. Missions + Composantes ─────────────────────────────────────────
        // SECTION 7 - TRAVEL AND ITS EXPENSES.
        // A Mission is one person travelling for one project: a purpose, a place, two dates.
        // A ComposanteMission is one line of what that trip cost: transport, hotel, per diem,
        // stamp duty, plane ticket.
        // Why two tables instead of a single "cost" column on the mission: the company's
        // expense rules treat each kind of cost differently (a per diem is a daily
        // allowance, stamp duty is a fixed legal fee, a plane ticket is a one-off). Splitting
        // them is what allows an expense report grouped by type, and it lets a mission have
        // two lines or five without changing the table.
        //
        // The purposes of the trips - workshops, reviews, training, audits, acceptance
        // sessions, closing meetings. They rotate over the projects so no two projects show
        // the same list.
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

        // Build the sub-list of projects that may have missions: everything except DRAFT
        // (not started, so nobody travels for it) and CANCELLED (stopped).
        // Why a separate list rather than a "continue" inside the main loop, as elsewhere:
        // the mission count below is "3 + (i % 4)", and i must count only the projects that
        // are kept. Using the index of allProjs would make the cadence of mission counts
        // jump every time a project is skipped.
        List<Project> missionProjs = new ArrayList<>();
        for (Project prj : allProjs) {
            if (prj.getStatus() != ProjectStatus.DRAFT && prj.getStatus() != ProjectStatus.CANCELLED)
                missionProjs.add(prj);
        }

        for (int i = 0; i < missionProjs.size(); i++) {
            Project prj = missionProjs.get(i);
            int nbMissions = 3 + (i % 4); // 3-6 missions per project
            // allProjs.indexOf(prj) walks back from the filtered list to the position in the
            // full list, because devIdx is indexed on the FULL list of 30 projects.
            // indexOf compares with equals(), and BaseEntity defines equals as "same class and
            // same non-null id", so it matches the right row - these projects have been saved
            // and therefore have ids. Had equals still been the default object identity, this
            // would work too here (the same objects are in both lists), but the id-based
            // version is what makes it safe.
            int[] members  = devIdx[allProjs.indexOf(prj)];
            User[] mUsers  = new User[members.length];
            for (int k = 0; k < members.length; k++) mUsers[k] = devs[members[k]];
            // A mission needs a traveller, so an empty team means no mission at all.
            if (mUsers.length == 0) continue;

            LocalDate pStart = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 6, 1);

            for (int m = 0; m < nbMissions; m++) {
                // Missions start one month after the project and then every month, so they
                // fall inside the project window and are easy to follow on a timeline.
                // "m + 1L" is a long because plusMonths takes a long; without the L Java would
                // widen the int anyway, so this is only a way of stating the type on the spot.
                LocalDate mStart = pStart.plusMonths(m + 1L);
                // Trips last 1 to 4 days, cycling. Why vary it: the hotel line below counts
                // nights from these two dates, so identical lengths would give an identical
                // description on every row.
                LocalDate mEnd   = mStart.plusDays(1 + m % 4);
                // Travellers take turns inside the team: modulo makes the m-th mission fall on
                // the (m mod team size)-th member, so a 4-person team sends 4 different people
                // before anyone travels twice.
                User mUser = mUsers[m % mUsers.length];

                // The mission is saved FIRST, on its own, because the expense lines below need
                // its generated id to fill their foreign key mission_id. This is why the
                // returned object is kept in a variable rather than the call being fired and
                // forgotten.
                Mission mission = missionRepo.save(Mission.builder()
                    .project(prj).user(mUser)
                    // (i + m) shifts both the purpose and the town per project AND per mission,
                    // so project 2's first trip is not project 1's first trip.
                    .objet(missionObjets[(i + m) % missionObjets.length])
                    .lieu(lieux[(i + m) % lieux.length])
                    .dateDebut(mStart).dateFin(mEnd)
                    .build());

                // Transport + Hébergement always
                // These two always exist: you cannot travel without getting there and sleeping
                // somewhere. Every mission therefore has at least two expense lines, which is
                // what keeps the expense screen from ever showing an empty mission.
                composanteRepo.save(ComposanteMission.builder().mission(mission)
                    .typeComposante(TypeComposante.TRANSPORT)
                    // Amount grows with the mission number and with the project index, so no
                    // two lines are identical: 60 TND plus 20 per mission plus 5 per project.
                    .montant(bd(String.valueOf(60 + (m * 20) + (i * 5))))
                    // Expenses are always in TND: they are paid locally, whatever currency the
                    // contract itself is signed in.
                    .devise("TND").description("Transport Tunis — " + lieux[(i + m) % lieux.length] + " A/R")
                    .build());
                composanteRepo.save(ComposanteMission.builder().mission(mission)
                    .typeComposante(TypeComposante.SEJOUR)
                    .montant(bd(String.valueOf(110 + m * 15)))
                    // The number of nights is recomputed from the two dates rather than stored,
                    // so the text can never contradict the dates next to it.
                    // Note the limit of getDayOfYear() arithmetic: it is only correct while the
                    // trip stays inside one calendar year. That holds here because every
                    // project above starts on the 1st or the 15th of a month, so a mission
                    // starts on the 1st or the 15th too and lasts at most 4 days. A trip
                    // straddling 31 December would print a negative number of nights.
                    .devise("TND").description("Hébergement hôtel " + (mEnd.getDayOfYear() - mStart.getDayOfYear() + 1) + " nuit(s)")
                    .build());
                // Every second mission also carries a meal allowance...
                // Why conditional: if all four kinds appeared on every mission, the expense
                // screen would show four identical blocks and the grouping by type would prove
                // nothing. Uneven lines are what make the totals differ from one trip to the
                // next.
                if (m % 2 == 0) {
                    composanteRepo.save(ComposanteMission.builder().mission(mission)
                        .typeComposante(TypeComposante.PERDIEM)
                        .montant(bd(String.valueOf(40 + m * 8)))
                        .devise("TND").description("Per diem repas déjeuner/dîner client")
                        .build());
                }
                // ...every third one a stamp duty. Fixed at 8 TND because a fiscal stamp is a
                // legal fixed fee, not something that varies with the trip.
                if (m % 3 == 0) {
                    composanteRepo.save(ComposanteMission.builder().mission(mission)
                        .typeComposante(TypeComposante.TIMBRE)
                        .montant(bd("8"))
                        .devise("TND").description("Timbre fiscal bon de commande mission")
                        .build());
                }
                // A plane ticket only on the FIRST mission of every fourth project. Why so
                // rare: international travel is rare, and one very large line among small ones
                // is what tests the expense screen's formatting and its sort by amount.
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
        // SECTION 8 - THE MONEY COMING IN.
        //   JalonFacturation - an invoicing milestone: "when this is delivered, we invoice
        //                      20 % of the contract". Its life is PREVU (planned) ->
        //                      FACTURE (invoice sent) -> PAYE (fully paid).
        //   Paiement         - one payment received against a milestone.
        //   Avenant          - a contract amendment: extra scope, extra money, or less.
        //
        // WHY MILESTONES COME IN TEMPLATES
        // A real contract does not invent its payment schedule freely: the company reuses a
        // handful of standard schedules depending on the kind of contract. The four templates
        // below are exactly that - 7 milestones for a big fixed-price build, 6 for a phased
        // delivery, 5 for a product-style project, 5 short ones for a consulting job.
        // Each template has a matching percentage list, and the two arrays are read with the
        // same index, so template 0's labels always go with template 0's percentages. If they
        // ever got out of step, a project would invoice "Go-Live" at the percentage meant for
        // "Démarrage".
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
        // The share of the contract invoiced at each milestone. Every row adds up to exactly
        // 100.0, and that is not a coincidence: the sum of the milestones IS the contract.
        // If a row summed to 95, the project would permanently look 5 % under-invoiced on the
        // billing screen and the "remaining to invoice" figure would never reach zero.
        // Each row also has the same number of entries as its label row above - 7, 6, 5, 5.
        double[][] jalonPctTemplates = {
            {8.0, 12.0, 20.0, 20.0, 15.0, 15.0, 10.0},
            {10.0, 10.0, 20.0, 25.0, 20.0, 15.0},
            {10.0, 20.0, 25.0, 25.0, 20.0},
            {15.0, 20.0, 30.0, 20.0, 15.0},
        };

        // The line between milestones already invoiced and milestones still planned.
        // It is a FIXED date, not LocalDate.now(), and that is deliberate: invoicing is
        // accounting, and an accounting period must not move on its own. Using "today" would
        // mean a milestone silently changing from PREVU to FACTURE between two demonstrations,
        // and the invoiced total on a screenshot would stop matching the application.
        // The price of a fixed date is that it ages: after 1 January 2026 the dataset stops
        // producing new invoices by itself.
        LocalDate billingCutoff = LocalDate.of(2026, 1, 1);

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // Only DRAFT is skipped. Cancelled projects DO get invoicing milestones, because
            // work already delivered before the cancellation still has to be invoiced - and
            // that is one of the more interesting cases to show on the billing screen.
            if (prj.getStatus() == ProjectStatus.DRAFT) continue;

            BigDecimal budget = prj.getInitialBudget() != null ? prj.getInitialBudget() : bd("500000");
            LocalDate pStart  = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);
            LocalDate pEnd    = prj.getEndDate()   != null ? prj.getEndDate()   : LocalDate.of(2026, 12, 31);
            // The length of the project in days. ChronoUnit.DAYS.between is written with its
            // full package name here because the class is used exactly once and is not
            // imported at the top.
            // Why days and not months: the milestones are spread evenly over the project by a
            // ratio, and a fraction of a month has no meaning.
            long dur          = java.time.temporal.ChronoUnit.DAYS.between(pStart, pEnd);

            // Pick one of the four schedules, cycling over the 30 projects, so all four are
            // present in the dataset several times.
            int tmpl          = i % jalonTemplates.length;
            String[] labels   = jalonTemplates[tmpl];
            double[] pcts     = jalonPctTemplates[tmpl];

            for (int j = 0; j < labels.length; j++) {
                // Spread the milestones evenly across the project life. (j + 1) rather than j,
                // so the FIRST milestone is not on the start date itself and the LAST one
                // lands exactly on the end date (ratio = 1.0).
                // The "(double)" cast is essential: without it, (j + 1) / labels.length is an
                // INTEGER division, so 1/7 would be 0 and 6/7 would be 0 too - every milestone
                // would be dated on the project start date and the invoicing schedule would be
                // a single pile.
                double ratio     = (double)(j + 1) / labels.length;
                LocalDate datePrev = pStart.plusDays((long)(dur * ratio));
                // "past" = this milestone was due before the accounting cut-off, so it has
                // already been invoiced.
                boolean   past   = datePrev.isBefore(billingCutoff);
                // Every fourth past milestone is fully PAYE, the others are FACTURE (invoiced,
                // still waiting for the money). Future ones stay PREVU.
                // Why mix the three: the cash-collection screen needs all three states at once
                // to be worth showing - an overdue invoice, a settled one and a forecast.
                JalonStatut statut = past ? (j % 4 == 3 ? JalonStatut.PAYE : JalonStatut.FACTURE) : JalonStatut.PREVU;
                // The invoice goes out 5 days after the milestone is reached. null for a
                // milestone still in the future, because there is no invoice date yet - that
                // null is meaningful data, not a missing value.
                LocalDate dateFact = past ? datePrev.plusDays(5) : null;
                // The modulo is a safety net: labels and pcts always have the same length
                // today, so j never overflows. Should a label ever be added to a template
                // without adding its percentage, this keeps the seeder running instead of
                // throwing ArrayIndexOutOfBoundsException at application start-up.
                double    pctVal   = pcts[j % pcts.length];
                // The amount = the contract budget times the milestone percentage, rounded to
                // two decimals. Stored as well as the percentage, because an amendment can
                // later change the budget and the already-issued invoice must not move.
                BigDecimal montant = budget.multiply(bd(String.format(Locale.US, "%.4f", pctVal / 100.0))).setScale(2, RoundingMode.HALF_UP);

                // Saved first, and kept, because the payment below needs the milestone id.
                JalonFacturation jalon = jalonRepo.save(JalonFacturation.builder()
                    .project(prj).label(labels[j])
                    .pourcentage(bd(String.format(Locale.US, "%.2f", pctVal)))
                    .montant(montant).datePrevue(datePrev)
                    .dateFacture(dateFact).statut(statut)
                    .build());

                // A payment row for every milestone that has been invoiced.
                // The "montant != null" half of the test can never be false: montant was just
                // computed from budget, which is never null. It is dead code kept as-is,
                // because this pass only adds comments.
                // Be ready for this question at the defence: the payment is written for the
                // full amount even when the milestone is only FACTURE, not PAYE. In the live
                // application, JalonService.recalculerStatut() flips a milestone to PAYE as
                // soon as its payments cover its amount, so these seeded rows are not what the
                // running code would produce. It is listed as a known inconsistency of the
                // demo dataset.
                if ((statut == JalonStatut.FACTURE || statut == JalonStatut.PAYE) && montant != null) {
                    paiementRepo.save(Paiement.builder()
                        .jalon(jalon).montantRecu(montant)
                        // Money arrives 12 days after the invoice - a plausible payment delay.
                        // The fallback branch cannot be reached (a payment is only created when
                        // the milestone is past, and a past milestone always has an invoice
                        // date), but it keeps the expression total so a future change of the
                        // condition above cannot produce a null payment date.
                        .datePaiement(dateFact != null ? dateFact.plusDays(12) : pStart.plusDays(30))
                        // A bank-transfer reference built to be readable and unique:
                        // "VIR-CRM-2026-J3-2025" = transfer, project code without its ENT-
                        // prefix, milestone number, year. Why build it rather than leave it
                        // empty: the accounting screen searches on this reference, and a search
                        // box over a column of nulls demonstrates nothing.
                        .reference("VIR-" + prj.getCode().replace("ENT-", "") + "-J" + (j + 1) + "-" + (dateFact != null ? dateFact.getYear() : 2025))
                        .build());
                }
            }

            // Avenants: up to three per live or finished project, from three independent
            // tests - every 3rd project, every 5th project, and every 7th project when the
            // contract is not in TND. A project can therefore end up with none, one, two or
            // (like p01, which is index 0 and is in EUR) all three.
            // An Avenant is a signed change to the contract: it moves the budget and the sold
            // workload. Only a live or finished contract can be amended - you cannot amend a
            // draft that was never signed, nor one that was cancelled.
            // The three "i % n" tests give overlapping but different sets of projects, so some
            // projects have no amendment, some have one, some have two or three. Why that
            // spread matters: the amendment tab must be shown both empty and full, and the
            // "budget after amendments" total must visibly differ from the initial budget on
            // some projects and not on others.
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
                // Every seventh project, and only when the contract is NOT in TND: a NEGATIVE
                // amendment, -4 % of budget and -10 days.
                // Why negative amounts exist at all: an amendment can also reduce a contract,
                // and a dataset with only positive ones would never test the subtraction, the
                // minus sign in the display, or a total that goes down.
                // Why only foreign-currency projects: the stated reason is a favourable
                // exchange rate, which can only happen when the contract is not already in the
                // company's own currency. Note the order of the test - "TND".equals(currency)
                // and not currency.equals("TND"), so a null currency returns false instead of
                // throwing a NullPointerException.
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
        // SECTION 9 - THE RISK REGISTER.
        // A Risk row carries a description, how likely it is (probabilite), how bad it would
        // be (impact), what is being done about it (planMitigation) and where it stands
        // (statut). Likelihood and impact use the same three-level scale NiveauRisque -
        // FAIBLE, MOYEN, ELEVE - which is what lets the screen draw the classic
        // probability-by-impact matrix.
        //
        // The descriptions are the eighteen risks that really recur on this kind of project:
        // late specifications, over-run, a key person unavailable, resistance to change,
        // single supplier, unstable test environment, regulation changing, legacy database
        // performance, exposed REST APIs, migration data late, version clashes, missing
        // client skills, late-delivery penalties, EUR/TND exchange rate, staff turnover,
        // hosting failure, competing priorities, poor subcontractor quality.
        // They are paired one-for-one with the mitigation list just below - index 0 of one
        // answers index 0 of the other - but the loop below reads the two lists with
        // DIFFERENT strides, so the pairing is not preserved in the final rows. See the note
        // there.
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
        // The eighteen answers, written to line up one-for-one with the eighteen risks above:
        // mitigations[8] ("WAF, API security audit") answers riskDescs[8] ("REST APIs exposed
        // on unsecured networks"), and so on for all eighteen.
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
        // values() returns the enum constants in declaration order, as a fresh array each
        // time it is called. Calling it ONCE here and reusing the array is why it sits
        // outside the loops: values() copies the array on every call, so calling it inside
        // would allocate about 150 throw-away arrays.
        // niveaux  = FAIBLE, MOYEN, ELEVE (3 values)
        // statuts  = OUVERT, MITIGE, FERME (3 values)
        // Using values() rather than writing the three constants by hand means that adding a
        // fourth level to the enum one day automatically appears in the seeded data instead
        // of being silently ignored.
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
                    // Stride 3 through the eighteen descriptions: risk 0, 3, 6, 9, 12, 15, 18...
                    // Side effect to know about: 18 is exactly the array length, so a project
                    // with the maximum of 7 risks (r goes up to 6) comes back to its starting
                    // description and shows that one twice.
                    .description(riskDescs[(i + r * 3) % riskDescs.length])
                    // Likelihood and impact are read one position apart in the same three-value
                    // array, so they are NEVER equal on the same row: a risk is never
                    // "FAIBLE / FAIBLE". Why that is wanted: identical pairs would all land on
                    // the diagonal of the probability-impact matrix and the chart would show a
                    // straight line instead of a spread.
                    .probabilite(niveaux[(i + r)     % niveaux.length])
                    .impact(     niveaux[(i + r + 1) % niveaux.length])
                    // ...but stride 2 through the eighteen mitigations.
                    // KNOWN DEFECT, do not defend it as a design choice: because the strides
                    // differ (3 against 2), only the first risk of each project (r = 0) gets
                    // the mitigation that actually answers its description. From r = 1 on, the
                    // two indexes drift apart and a risk about the EUR/TND exchange rate can be
                    // shown next to a mitigation about a web application firewall. The data
                    // still exercises every screen, but a jury reading a single row may find
                    // the pair meaningless. It is listed in the issues of this pass; fixing it
                    // means reading both arrays with the same index.
                    .planMitigation(mitigations[(i + r * 2) % mitigations.length])
                    .statut(statuts[(i + r) % statuts.length])
                    .build());
            }
        }

        // ── 10. Livrables ─────────────────────────────────────────────────────
        // SECTION 10 - THE CONTRACTUAL DELIVERABLES.
        // A Livrable is a document or a product the contract obliges the company to hand
        // over: a specification, an architecture file, a test report, an acceptance minute.
        // It has a title, a due date and a status along EN_ATTENTE -> EN_COURS -> LIVRE ->
        // VALIDE.
        // Why a table of its own rather than a line in the project description: the due date
        // is what drives the late-deliverable alert, and the status is what a client asks
        // about first. Both need to be queried, not read in prose.
        //
        // Twenty real names of deliverables, with their French abbreviations spelled out so a
        // reader who does not know them can follow: CdCF = functional requirements,
        // DAT = technical architecture file, STI = integration specifications, JTR = the
        // acceptance test set, PVRT / PVRD = the technical and final acceptance minutes,
        // NMP = the go-live note, REX = the lessons-learned report.
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
        // Honest note: livStatuts is never used. The status of each deliverable is decided by
        // the if/else chain below instead, which is the right way here - a status must follow
        // the due date, not a position in the enum. The line is dead and is left untouched,
        // because this pass only adds comments.
        StatutLivrable[] livStatuts = StatutLivrable.values();

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // Cancelled projects DO keep their deliverables: whatever was produced before the
            // stop still exists and still has to be shown. Only drafts are skipped.
            if (prj.getStatus() == ProjectStatus.DRAFT) continue;
            int nbLiv = 4 + (i % 6); // 4-9 livrables per project
            LocalDate pStart = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);

            for (int l = 0; l < nbLiv; l++) {
                // One deliverable per month from the second month of the project.
                LocalDate echeance = pStart.plusMonths(l + 1L);
                StatutLivrable sl;
                // THE STATUS LADDER. This chain is what makes the deliverables tab look like a
                // real project instead of a list of identical rows. It is read top to bottom
                // and the first matching branch wins.
                if (prj.getStatus() == ProjectStatus.COMPLETED) {
                    // A finished project: everything is accepted, except the last two, which
                    // are only handed over. Why keep two unaccepted: the final acceptance
                    // minutes really are the last thing signed, often after the work is over.
                    sl = l < nbLiv - 2 ? StatutLivrable.VALIDE : StatutLivrable.LIVRE;
                } else if (echeance.isBefore(refDate.minusMonths(4))) {
                    // Due more than four months ago: settled long since. One in three formally
                    // accepted, the rest handed over - acceptance always lags delivery.
                    sl = l % 3 == 0 ? StatutLivrable.VALIDE : StatutLivrable.LIVRE;
                } else if (echeance.isBefore(refDate)) {
                    // Due recently: half handed over, half still being written. This is the
                    // band that produces LATE deliverables - EN_COURS with a due date in the
                    // past - which is exactly what the alert screen needs in order to show
                    // anything at all.
                    sl = l % 2 == 0 ? StatutLivrable.LIVRE : StatutLivrable.EN_COURS;
                } else {
                    // Still in the future: one in three already started, the rest waiting.
                    sl = l % 3 == 0 ? StatutLivrable.EN_COURS : StatutLivrable.EN_ATTENTE;
                }

                livrableRepo.save(Livrable.builder()
                    .project(prj)
                    // (i + l) again, so project 5 does not start its list at the same title as
                    // project 4.
                    .titre(livTitles[(i + l) % livTitles.length])
                    // The description reuses the title in lower case and names the person
                    // responsible. Built rather than fixed so the description can never
                    // contradict the title above it.
                    // getFirstName() is only reached when getChefProjet() is not null - Java
                    // evaluates the condition of the "? :" first - so this cannot throw a
                    // NullPointerException; "Équipe projet" is the fallback wording when a
                    // project has no manager.
                    .description("Livrable contractuel — " + livTitles[(i + l) % livTitles.length].toLowerCase()
                        + ". Responsable : " + (prj.getChefProjet() != null ? prj.getChefProjet().getFirstName() : "Équipe projet") + ".")
                    .dateEcheance(echeance)
                    .statut(sl)
                    .build());
            }
        }

        // ── 11. Parties Prenantes ─────────────────────────────────────────────
        // SECTION 11 - THE STAKEHOLDER REGISTER, on the client side.
        // A PartiePrenante is somebody outside the company who has a say in the project: the
        // sponsor who signs, the IT director, the functional owner, the funder's
        // representative. Each one carries two judgements on the same three-level scale as a
        // risk: how much weight this person has (influence) and how much this person cares
        // (interet). Those two together are what tells a project manager whom to inform first
        // when something changes.
        //
        // Five groups of five invented names. A project takes one group and uses the first
        // few names of it. Why groups rather than one long list: stakeholders of the same
        // project should feel like one organisation, and cycling through a single flat list
        // would mix them at random.
        String[][] ppPersonnes = {
            {"Rachid Hamdi","Sihem Ben Amor","Mourad Chatti","Houda Rouissi","Taher Nasri"},
            {"Nadia Bouhlel","Anis Mabrouk","Leila Chaabane","Youssef Trabelsi","Imen Ben Salah"},
            {"Habib Karray","Faiza Khemiri","Sami Jelassi","Roua Baccari","Walid Mansouri"},
            {"Dalila Ferchichi","Kamel Dridi","Sana Ouerghi","Mehdi Riahi","Olfa Zouari"},
            {"Slim Ben Fredj","Nour Gueddiche","Hajer Azouzi","Tarek Saadaoui","Rim Meddeb"},
        };
        // The eight job titles a client-side stakeholder can hold, from the executive sponsor
        // down to the security officer. They are also the raw material of the generated
        // e-mail address further down, which is why several of them end with an abbreviation
        // in brackets - and why that turns out to matter, see the note on the regular
        // expressions below.
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
        // The same three-level scale as the risks (FAIBLE, MOYEN, ELEVE), reused here for
        // influence and interest. Reusing one enum rather than declaring a second identical
        // one means the two screens sort and colour their levels in exactly the same way.
        NiveauRisque[] ppNiveaux = NiveauRisque.values();

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // A draft has no client yet and a cancelled project no longer has stakeholders to
            // manage, so neither gets a register.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            int nbPP    = 2 + (i % 5); // 2-6 parties prenantes
            // Turn the project code into something that can serve as a fake client domain:
            // "ENT-CRM-2026" -> lower case "ent-crm-2026" -> drop the "ent-" prefix -> remove
            // the remaining hyphens -> "crm2026". Used below as "...@crm2026.tn".
            // Why strip the hyphens: a hyphen is legal in a domain name, but this way the
            // address reads like one word and cannot start or end with a hyphen, which is not
            // legal.
            String code = prj.getCode().toLowerCase().replace("ent-", "").replace("-", "");
            String[] noms = ppPersonnes[i % ppPersonnes.length];

            // Math.min is the real guard here: nbPP can be 6 but a group only holds 5 names.
            // Without it the loop would run past the end of the array and throw
            // ArrayIndexOutOfBoundsException, which inside this one transaction means the
            // application starts with no seeded data at all.
            for (int p = 0; p < Math.min(nbPP, noms.length); p++) {
                partieRepo.save(PartiePrenante.builder()
                    .project(prj)
                    .nom(noms[p])
                    .fonction(fonctions[(i + p) % fonctions.length])
                    // THE TWO REGULAR EXPRESSIONS. An e-mail address is built from the job
                    // title, in three steps:
                    //   toLowerCase()                -> "directeur général / sponsor exécutif"
                    //   replaceAll("[^a-z]", ".")    -> every character that is NOT a plain
                    //        lower-case letter becomes a dot. That covers spaces, the slash,
                    //        the brackets and the accented letters (é, è), which are not in
                    //        the a-z range. Result:
                    //        "directeur.g.n.ral...sponsor.ex.cutif"
                    //        Why it is needed: a space or a slash inside an address would make
                    //        it unusable, and an accent would break older mail systems.
                    //   replaceAll("\\.{2,}", ".")   -> "{2,}" means "two or more in a row", so
                    //        any run of dots collapses into one. Result:
                    //        "directeur.g.n.ral.sponsor.ex.cutif"
                    //        Why it is needed: without it the address would contain "..." ,
                    //        which no mail server accepts.
                    // The "\\." is a dot that has been escaped twice: once for Java (so the
                    // string really contains \.) and once for the regular expression (so it
                    // means a literal dot and not "any character").
                    // KNOWN DEFECT: five of the eight job titles end with a closing bracket -
                    // "(DSI)", "(RUC)", "(DAF)", "(RSSI)", "(DTC)". That bracket becomes a
                    // trailing dot, so the address comes out as
                    // "...information.dsi.@crm2026.tn". A dot immediately before the @ is not a
                    // valid address. The column has no format check, so the row is stored, but
                    // an editing form that validates the address will reject it. Listed in the
                    // issues of this pass.
                    .email(fonctions[(i + p) % fonctions.length].toLowerCase()
                        .replaceAll("[^a-z]", ".").replaceAll("\\.{2,}", ".")
                        + "@" + code + ".tn")
                    // A Tunisian-looking phone number: "+216 7x nnn nnn". %03d pads a number
                    // with leading zeros to exactly three digits, so 7 prints as "007" and the
                    // groups always line up in a column on screen. The "% 1000" keeps the last
                    // group inside three digits.
                    .telephone("+216 7" + (i % 5) + " " + String.format("%03d", (p + 1) * 100 + i) + " " + String.format("%03d", (i + p) * 17 % 1000))
                    // Read one position apart in the three-value array, exactly as with a
                    // risk, so influence and interest are never equal. That is what scatters
                    // the stakeholders across the four quadrants of the influence/interest map
                    // instead of piling them on its diagonal.
                    .influence(ppNiveaux[(i + p)     % ppNiveaux.length])
                    .interet(  ppNiveaux[(i + p + 1) % ppNiveaux.length])
                    .build());
            }
        }

        // ── 12. Demandes de Changement ────────────────────────────────────────
        // SECTION 12 - CHANGE REQUESTS.
        // A DemandeChangement is a request to change the scope of a project after it has
        // started: who asked, what for, how urgent, and the decision. Its status runs
        // EN_ATTENTE -> APPROUVE or REJETE.
        // How it differs from an Avenant in section 8: a change request is the DISCUSSION, an
        // amendment is the SIGNED contract that may come out of it. Many requests are
        // refused, and a refused request never becomes an amendment - which is why the two
        // are separate tables and why the counts do not match.
        //
        // Fifteen requests of the kind that really arrive mid-project: add BI reporting,
        // rework a validation workflow, plug in the client's single sign-on, add a live
        // dashboard, upgrade the database, add push notifications, and so on.
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
        // The two arrays have different lengths, 4 and 3, and both are walked with the same
        // counter (i + dc). Because 4 and 3 share no common factor, the pair
        // (priority, status) only repeats after 12 steps - so the dataset naturally contains
        // a critical request that was refused, a low-priority one that was approved, and
        // every other combination. That is what makes the filters on the change-request
        // screen worth trying.
        PrioriteChangement[] prios = PrioriteChangement.values();
        StatutChangement[]   scStatuts = StatutChangement.values();

        for (int i = 0; i < allProjs.size(); i++) {
            Project prj = allProjs.get(i);
            // Nothing to change on a project that has not started or has been stopped.
            if (prj.getStatus() == ProjectStatus.DRAFT || prj.getStatus() == ProjectStatus.CANCELLED) continue;
            int nbDC  = 2 + (i % 4); // 2-5 demandes per project
            // The project manager is the one who files the request. demandeur is a foreign key
            // to users, so it needs a real person; d1 is the fallback for a project with no
            // manager, which none of the 30 above actually is.
            User dem  = prj.getChefProjet() != null ? prj.getChefProjet() : d1;
            LocalDate pStart = prj.getStartDate() != null ? prj.getStartDate() : LocalDate.of(2024, 1, 1);

            for (int dc = 0; dc < nbDC; dc++) {
                PrioriteChangement  prio = prios[  (i + dc)     % prios.length];
                StatutChangement    sc   = scStatuts[(i + dc)   % scStatuts.length];
                // Requests arrive two months in, then every three months. Why not from day
                // one: nobody asks to change the scope before the work has begun.
                LocalDate dateDemande = pStart.plusMonths(2L + dc * 3L);
                // THE RULE THAT TIES THE TWO COLUMNS TOGETHER: a decision date exists only if
                // a decision was taken. Still EN_ATTENTE means null.
                // Why it matters: null here is real information, not a hole. A screen showing
                // "waiting since..." reads dateDemande, and a screen showing "decided on..."
                // reads dateDecision; filling both on a pending request would make a request
                // look answered when it is not.
                LocalDate dateDecision = sc != StatutChangement.EN_ATTENTE ? dateDemande.plusDays(7 + dc * 3) : null;

                demandeRepo.save(DemandeChangement.builder()
                    .project(prj).demandeur(dem)
                    .titre(dcTitres[(i + dc) % dcTitres.length])
                    // The description is built so that its numbers grow with the request
                    // number: request #1 costs 20 extra days and 2 weeks, #2 costs 40 days and
                    // 4 weeks, and so on. It is plain text, not a column the application
                    // computes with - the figures are there to make the screen readable.
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
        // SECTION 13 - THE INTERNAL QUOTE, AND THE ONE PLACE WHERE WHAT IS *MISSING* IS THE
        // POINT. Expect a question on this section at the defence.
        //
        // WHAT A DEVIS INTERNE IS
        // The DI is the company's internal cost and margin sheet for a project, copied from
        // the Excel file the company really uses. It has three parts, in this order:
        //   HONORAIRES   - the fees: one line per contractual profile, with how many days are
        //                  sold, at what price, and what that profile costs internally.
        //   FRAIS        - direct expenses: travel, per diem, plane tickets, accommodation.
        //   AUTRES_FRAIS - the provisions and taxes that come off the top: risk provision,
        //                  registration duties, bank guarantees, withholding tax, the
        //                  late-delivery penalty provision.
        //
        // WHAT THIS SEEDER WRITES, AND WHAT IT REFUSES TO WRITE
        // It writes the SHAPE: which section a line belongs to, its position in that section,
        // the name of the profile or the expense, and its unit. It writes NO money at all -
        // not one price, not one cost, not one rate. Those columns exist in the table and
        // stay NULL.
        // This is the AMENDED DECISION 2026-07-05, written in the same words at the top of
        // the Flyway migration V23__devis_interne.sql and in the specification F-AFF-13: the
        // structure of the DI is delivered as an EMPTY TEMPLATE; each deployment types in its
        // own figures. The company's real daily costs and real margins are the most sensitive
        // data it has, and they would otherwise be sitting in a source file that a jury reads
        // and an archive keeps.
        // What it costs to do it this way: the DI screen opens on empty amount cells in a
        // demonstration. That is accepted on purpose, and it is the right answer to give.
        // Note also that the DI's computed totals are derived every time the rows are read,
        // never stored in a column - so an empty template is genuinely empty, with no total
        // left behind from an earlier save.
        //
        // The ten fee lines, in the order they appear on the sheet: from the project director
        // at the top down to the security expert. The order is what the "ordre" column
        // records, and the sheet is read top to bottom by whoever prices the project.
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
        // The five direct-expense lines. They match the expense types of section 7 - travel,
        // per diem, plane ticket, accommodation - which is deliberate: what a mission really
        // costs must be comparable with what was quoted for it.
        String[] diFrais = {
            "Frais de déplacement mission terrain (TND/mission)",
            "Per diem équipe projet (repas + transport local)",
            "Billet avion mission internationale",
            "Frais hébergement déplacement longue durée",
            "Location salle de formation / séminaire",
        };
        // The five provisions and taxes. These are the lines whose unit is a PERCENTAGE of
        // the amount sold rather than a number of days, which is why they need a section of
        // their own: PPR = risk provision, registration duties, bank guarantees,
        // RS = withholding tax on corporate income, PPP = the provision kept against
        // late-delivery penalties.
        // The "5 %" and "RS 5 %" inside these labels are TEXT, part of the line name. The
        // rate the application would actually compute with lives in the taux_pourcentage
        // column, and that column is left NULL here like every other money column.
        String[] diAutresFrais = {
            "Provision Pour Risques (PPR – 5 % budget vendu TND)",
            "Droits d'enregistrement contrat (timbre + enregistrement)",
            "Frais bancaires, cautions et garanties de bonne fin",
            "Retenue à la source IS / impôts directs (RS 5 %)",
            "Provision pour pénalités de retard (PPP)",
        };

        // Only ACTIVE and COMPLETED projects get a DI template - 18 of the 30.
        // Why this narrower list than anywhere else: a DI is priced for a contract that
        // exists. A DRAFT has not been priced yet, and a CANCELLED one was abandoned; giving
        // either of them a cost sheet would suggest a costing exercise that never took place.
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
                    // "ordre" restarts at 1 in EACH section, which is why l + 1 and not a
                    // counter running across the three loops. Why it must restart: the sheet
                    // is read section by section, and the screen sorts the lines of one
                    // section by this number. A single running counter would make the first
                    // expense line number 11, which matches nothing on the paper document.
                    .project(prj).section(SectionDi.HONORAIRES).ordre(l + 1)
                    .profilContractuel(diHonoraires[l])
                    // Fees are sold by the day: "H-Jour" is one person for one day.
                    .unite("H-Jour")
                    // tous champs financiers intentionnellement null (décision 2026-07-05)
                    // Translation: every financial field is intentionally left null
                    // (decision of 2026-07-05). This is not an oversight and it is not a
                    // to-do. Nothing is passed for chargeVendueJh, prixVenteUnitaire,
                    // quantiteInterneJh, coutUnitaireTcc, fraisDivers, fraisGeneraux,
                    // coutImpots or tauxPourcentage, so the builder leaves them null and the
                    // INSERT writes NULL into those columns. All of them are nullable in V23
                    // precisely so that this empty template is a legal row.
                    .build());
            }
            // FRAIS section
            for (int l = 0; l < diFrais.length; l++) {
                ligneDiRepo.save(LigneDi.builder()
                    .project(prj).section(SectionDi.FRAIS).ordre(l + 1)
                    .profilContractuel(diFrais[l])
                    // Expenses are quoted as a lump sum, not per day, so the unit changes.
                    .unite("Forfait")
                    // Same rule as above: no amount is set. The unit is metadata, not money.
                    .build());
            }
            // AUTRES_FRAIS section
            for (int l = 0; l < diAutresFrais.length; l++) {
                ligneDiRepo.save(LigneDi.builder()
                    .project(prj).section(SectionDi.AUTRES_FRAIS).ordre(l + 1)
                    .profilContractuel(diAutresFrais[l])
                    // These lines are a percentage of the total sold, so their unit is "%".
                    // The percentage ITSELF is not written - that is the taux_pourcentage
                    // column, and it stays null like every other figure.
                    .unite("%")
                    .build());
            }
        }

        // The closing line, and the only proof on the console that the seeding really
        // finished. The "{}" placeholders are filled by SLF4J only if the INFO level is
        // switched on, so nothing is concatenated when logging is off.
        // Why log at the END and not at the start: reaching this line means every section
        // completed. The transaction still has to commit after the method returns, so a
        // failure is still possible afterwards - but a missing line here always means the
        // seeding stopped somewhere in the middle.
        log.info("EnterpriseDataSeeder — terminé : {} utilisateurs, {} projets, ressources + TCC + workload + KPIs + missions + facturation + gouvernance + DI.",
            allEntUsers.size(), allProjs.size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    // Four small private methods that hide the noise of run(). They are private because
    // nothing outside this class has any reason to call them, and keeping them private means
    // Spring never wraps them in a proxy - a self-call to a private method could not be
    // intercepted anyway, which is exactly why the @Transactional above sits on run() and
    // not on these.

    /**
     * Finds one RBAC role by its name, or returns null if it does not exist.
     * WHY null AND NOT AN EXCEPTION: run() checks the three roles together and logs one
     * clear warning. Throwing here would stop the whole application from starting because a
     * DEMO dataset could not be built, which is out of proportion.
     * Optional.orElse(null) unwraps the Optional the repository returns; the Optional is
     * what forces the caller to think about the missing case instead of getting a surprise
     * NullPointerException later.
     */
    private Role roleByName(String name) {
        return roleRepo.findByName(name).orElse(null);
    }

    /**
     * Returns the user with this e-mail, creating him if he is not there yet, and gives back
     * the saved entity with its generated id.
     *
     * WHY "FIND FIRST, THEN CREATE" AND NOT A PLAIN save()
     * users.email carries the unique index uk_users_email on rows where deleted = false. A
     * blind insert of an address that already exists would throw a constraint violation and
     * roll back the whole seeding transaction. This shape makes the helper safe to call even
     * on a database that already holds some of these people.
     * It is the second line of defence, behind the sentinel check in run(): the sentinel
     * stops the whole seeder, this one protects a single row.
     *
     * orElseGet takes a lambda - a piece of code run ONLY if the Optional is empty. That is
     * the difference with orElse, which would build and save the user every time, even when
     * one was found, and would therefore insert a duplicate on every start.
     *
     * The flags: active(true) so the account can log in, firstLogin(false) so the
     * application does not force a password change on first connection - which would block a
     * demonstration on its very first screen.
     */
    private User user(String first, String last, String email, String pwd, Role role) {
        return userRepo.findActiveByEmailWithRole(email).orElseGet(() ->
            userRepo.save(User.builder()
                .firstName(first).lastName(last).email(email)
                .passwordHash(pwd).active(true).firstLogin(false)
                .role(role).build()));
    }

    /**
     * Builds and saves ONE project row from twenty positional arguments, and returns the saved
     * entity carrying its generated database id.
     *
     * WHY THIS HELPER EXISTS
     * Section 3 writes thirty projects. Spelled out in full, each one would be a fifteen-line
     * Project.builder() chain, so the section would run past four hundred lines and no reader
     * could compare one project with the next. Folding each project into a single call is what
     * lets the thirty lines line up in columns, so a wrong status or a wrong currency is caught
     * by the eye instead of by a screen that shows nonsense weeks later.
     *
     * THE PRICE OF TWENTY POSITIONAL ARGUMENTS, AND WHY IT IS PAID HERE
     * Arguments of the same type standing side by side can be swapped and the code still
     * compiles: hand the funder where the client goes, or the warranty workload where the sold
     * workload goes, and a perfectly plausible - and wrong - project sheet is stored. That is
     * exactly the mistake the @Builder on the Project entity exists to prevent. It is accepted
     * here for one reason: this method is private, it has one caller, and its thirty call sites
     * sit aligned in columns directly above, where a swapped value shows as a value in the
     * wrong column. This shape would NOT be acceptable on a service method that real user
     * input reaches.
     *
     * WHY THE DATES AND THE BUDGET ARRIVE AS STRINGS
     * The call sites write "2025-09-01" and "1800000", and the conversion happens here.
     * LocalDate.parse reads the ISO form yyyy-MM-dd, so a typo such as "2025-13-01" throws at
     * start-up, where it is obvious, instead of being quietly stored. The budget is built with
     * new BigDecimal(String), which keeps the digits exactly as written - the habit that
     * matters as soon as an amount has centimes, because new BigDecimal(0.1) built from a
     * double is 0.1000000000000000055511151231257827, not 0.1.
     *
     * @return the saved Project. The caller MUST keep this returned object: only it carries the
     *         id that the team rows, timesheets, milestones and DI lines below use as their
     *         foreign key. Keeping the builder's own object instead would give rows pointing at
     *         a project id of null.
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
            // These two are part of the SECURITY model, not only of the project sheet.
            // ProjectRepository.findAccessibleProjectIdsByEmail matches on chefProjet.email, so
            // being named here is one of the two ways a user may open a project at all - the
            // other is being an active member of its team, which section 4 writes.
            // ProjectScopeInterceptor applies that list to every /api/projects/{id}/** URL
            // (ADR-021). Leave chefProjet null and the project manager, although he holds every
            // permission, is refused on the very project he runs.
            .director(director).chefProjet(chef)
            // Both are null for the five DRAFT projects on purpose: a contract that is not
            // signed has no client and no funder yet. Storing a placeholder such as "N/A" would
            // hide the case the screens must handle - a project sheet with empty fields.
            .client(client).funder(funder)
            // LocalDate.parse expects exactly yyyy-MM-dd and throws DateTimeParseException on
            // anything else. The pair also has to satisfy chk_project_dates in the database,
            // which since V17 requires end_date >= start_date: an end date typed before the
            // start date would abort the whole seeding transaction rather than be stored.
            .startDate(LocalDate.parse(start))
            .endDate(LocalDate.parse(end))
            // The signed budget, in the project currency, never changed afterwards. Sections 6
            // and 8 read it back to derive every amount they write, which is why it must be an
            // exact BigDecimal: a budget off by a fraction would spread that error into the KPI
            // snapshots and the invoicing milestones of that project.
            .initialBudget(new BigDecimal(budget))
            .businessModel(bm).engagementType(et)
            // The net margin sold at signature, stored as a FRACTION: 0.38 means 38 %. The
            // column is NUMERIC(7,4), so 0.38 is kept as 0.3800. Why a fraction and not 38.00:
            // every percentage in PMS is stored the same way, so no screen has to remember
            // which of the two conventions a given column uses.
            .margeNetteVendue(marge)
            .contractId(contractId)
            // THE @Builder.Default TRAP. The Project entity declares currency = "TND" and
            // exchangeRateToTnd = ONE with @Builder.Default, but that default only applies when
            // the builder method is NOT called: passing null explicitly overwrites it with
            // null. These two guards are what makes the defaults hold even then.
            // No call site above passes null today (every project states "TND" or "EUR" with
            // its rate), so the guards never fire at present. They matter for the project added
            // tomorrow: a project stored with a null currency and a null rate could not be
            // converted into TND at all, and every consolidated total across the portfolio
            // would either skip it or fail.
            .currency(currency != null ? currency : "TND")
            .exchangeRateToTnd(xRate != null ? xRate : BigDecimal.ONE)
            // Null for the DRAFT projects, like the client and the funder above: nothing has
            // been priced, so there is no licence budget, no sold workload and no warranty
            // share to record.
            .licenseSubcontractBudget(licenseBudget)
            .soldWorkloadDays(soldWl)
            .warrantyWorkloadDays(warrantyWl)
            // "archived" is DERIVED from the status instead of being a twenty-first argument.
            // Why: the flag only decides which of the two lists a project appears in -
            // ProjectRepository.findAllActive filters archived = false, findAllArchived filters
            // archived = true. Deriving it means the six COMPLETED and three CANCELLED projects
            // cannot be forgotten one by one, which would leave nine rows cluttering the active
            // list and an archive screen with nothing in it to show.
            // Be ready for this at the defence: the running application is STRICTER than this
            // line. ProjectService.archive() refuses to archive anything that is not COMPLETED,
            // so a user could never archive a CANCELLED project through the interface. The
            // seeder writes the column directly and bypasses that rule. It is listed in the
            // issues of this pass.
            .archived(status == ProjectStatus.COMPLETED || status == ProjectStatus.CANCELLED)
            .build());
    }

    /**
     * Turns a text like "0.2800" into the exact BigDecimal 0.2800. A three-character shortcut
     * for new BigDecimal(String), used several hundred times above.
     *
     * WHY A HELPER FOR SOMETHING SO SMALL
     * Not to save typing, but to make ONE habit visible and impossible to skip. Every amount
     * in PMS is a BigDecimal built FROM A STRING. BigDecimal is the exact decimal type: it
     * keeps the digits as written, one by one. A double cannot - it stores numbers in binary,
     * and 0.1 has no exact binary form, so new BigDecimal(0.1) built from a double is really
     * 0.1000000000000000055511151231257827.
     * Concretely, without this rule a TCC rate of 0.2800 multiplied over thousands of timesheet
     * days drifts, and the same total comes out as 12 345.67 on the project screen and
     * 12 345.68 on the invoicing screen - the kind of one-millime gap an accountant notices
     * immediately and nobody can explain.
     * Because the parameter is declared as String, the compiler refuses bd(0.28) outright. That
     * is the real value of the helper: the mistake cannot be made.
     *
     * WHY static
     * It touches no field of this class, so it does not need the object. Marking it static
     * states that plainly and lets it be called from anywhere in the class without a thought
     * about instance state.
     *
     * WHAT HAPPENS ON BAD INPUT: new BigDecimal throws NumberFormatException, which is why the
     * calls above always go through String.format(Locale.US, ...) first. A French or Tunisian
     * default locale would format 14.5 as "14,5", and bd("14,5") would throw and roll back the
     * whole seeding transaction.
     *
     * @param val the number written with a DOT as decimal separator, for example "0.2800".
     * @return that exact value as a BigDecimal, keeping the number of decimals given.
     */
    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}

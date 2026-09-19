package com.pms.project.repository;

import com.pms.project.entity.Project;
import com.pms.project.entity.ProjectStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * WHAT THIS FILE IS
 * Database access for one table: projects, the central table of the application. The
 * project is what every other module hangs from - risks, deliverables, milestones,
 * sprints, workload, invoices, KPI, internal quote. A repository is the only place in the
 * application that talks to the database for that table. It carries no business rule and
 * no permission check; both live in the services above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> ProjectController (/api/projects)
 *           -> ProjectService    (permission, transaction, financial masking BR-050)
 *           -> ProjectRepository (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table projects
 * The Project rows never leave the server: ProjectMapper (MapStruct) turns them into the
 * ProjectResponse record, and that record is what becomes JSON.
 * What makes this file different from the other repositories of the project is its long
 * list of callers:
 *   * ProjectScopeService calls findAccessibleProjectIdsByEmail on every request that
 *     touches a project - this is the query behind ADR-021;
 *   * fifteen services (agile, billing, governance, mission, workload, kpi, team, DI...)
 *     call findActiveById inside their own loadProject() helper, to answer 404 on an
 *     unknown or soft-deleted project before doing anything else;
 *   * the demo loaders in shared/config do not all use the same methods: DemoDataSeeder and
 *     EnterpriseDataSeeder call existsByCodeAndDeletedFalse as their "already seeded?" test
 *     and then the inherited save() to write their demo projects, while AgileDemoSeeder only
 *     reads findAllActive, to find the projects it must hang its sprints on.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * Almost nothing in the application would compile. More precisely, the methods below add
 * three things the built-in JpaRepository methods cannot do:
 *   1. they hide the rows flagged deleted = true (soft delete) and, on the active lists,
 *      the rows flagged archived = true;
 *   2. they load the two users of a project (director and chef de projet) in the SAME SQL
 *      statement, because the mapper reads their names right after;
 *   3. they answer the scope question of ADR-021 in one single query (M-6).
 *
 * TWO WORDS THAT LOOK ALIKE AND ARE NOT
 *   deleted  = soft delete. The row is gone for the users but stays in the database for
 *              the audit trail. Every method below filters it out.
 *   archived = a finished project put aside (column added by migration V16). It stays
 *              fully readable, but in its own "archived projects" list. Only the ACTIVE
 *              lists exclude it; findActiveById does not, which is what lets a user open
 *              an archived project and unarchive it.
 *
 * HOW THIS FILE RELATES TO THE OTHER FILE OF THE PACKAGE
 * LigneDiRepository, next to it, reads the child table lignes_di (the internal quote).
 * DevisInterneService uses both: findActiveById here gives the project and its exchange
 * rate, then LigneDiRepository gives the lines to which that rate is applied. One
 * repository per table is the rule of this code base.
 *
 * SECURITY - the two protections that are NOT written in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_PROJECT')"), 'CREATE_PROJECT',
 *    'EDIT_PROJECT'... sits on the SERVICE methods of ProjectService, never on the
 *    controller and never here. The code tests a permission code, never a role name, so
 *    an administrator can move a permission from one role to another while the
 *    application is running.
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {projectId} of the URL
 *    /api/projects/{id}/** and answers 403 when that project is outside the perimeter of
 *    the caller. Having the permission is NOT enough.
 *    Note the exact shape of that rule, because it explains the last method of this file:
 *    the interceptor can only work on a URL that carries an id. The LIST endpoints carry
 *    none, so they cannot be protected that way - ProjectService filters those lists
 *    itself, using the ids returned by findAccessibleProjectIdsByEmail.
 * So a method of this file is never safe on its own: called from a new place without
 * going through a service, it would skip both checks.
 */
// Nothing implements this interface by hand, and that is normal: at start-up Spring Data
// JPA reads the interfaces that extend JpaRepository, turns each @Query text below into a
// real SQL statement, and builds the implementation itself (a "proxy" object) which it
// hands to the services that asked for a ProjectRepository. No @Repository annotation is
// needed, because extending JpaRepository is already the signal Spring looks for.
// A useful side effect: the @Query texts are parsed at start-up, so a typo in one of them
// stops the application immediately instead of failing on the day a user opens that
// screen.
// The two types between < > are generics - they tell the proxy what to work on:
//   Project = the entity, so the table read is projects,
//   Long    = the type of the @Id field (inherited from BaseEntity), so findById takes a
//             Long.
// Example of what these generics buy: findById(1L) gives back an Optional<Project>
// already typed. Without them the method would return Object, every caller would need a
// cast, and a ClassCastException would be waiting at run time.
// JpaRepository also brings in, for free, save(), findById(), findAll(), count(),
// deleteById()... The services use save() a lot but never deleteById(): deleting a
// project means setting deleted = true, never erasing the row, because dozens of other
// tables point at it.
public interface ProjectRepository extends JpaRepository<Project, Long> {

    // WHAT: gives back every project that is neither deleted nor archived, sorted by
    //       code, with its two users already loaded.
    // WHY : this is the query behind the main project list. Five decisions are packed
    //       into one line of JPQL (JPQL looks like SQL but is written on the Java
    //       classes: "Project p" is the entity name, not the table name; Hibernate
    //       translates it into real SQL).
    //
    //   (a) "LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet" - both links are
    //       mapped @ManyToOne(fetch = FetchType.LAZY) on Project, so Hibernate would
    //       normally leave them as empty placeholders and run one extra SELECT the first
    //       time each one is read. ProjectMapper reads the id AND the full name of both
    //       users for EVERY row. JOIN FETCH brings the user rows in the same statement.
    //       WITHOUT IT: 30 projects means 1 query plus up to 60 more, one per user - the
    //       classic "N+1 select" problem. And because application.yml sets
    //       "open-in-view: false", a lazy link can only be loaded while the service
    //       transaction is still open, so mapping done after the service returned would
    //       not just be slow, it would fail with LazyInitializationException.
    //
    //   (b) LEFT and not a plain JOIN: director_id and chef_projet_id are NULLABLE
    //       columns (migration V5). A project can exist before anybody is named on it -
    //       naming the chef even needs its own permission, ASSIGN_CHEF_PROJET.
    //       WITHOUT the LEFT: a plain JOIN keeps only the rows that have a match, so a
    //       brand new project with no chef would silently vanish from the list, with no
    //       error anywhere to explain why.
    //
    //   (c) "p.deleted = false" - soft delete, see the note in the header above.
    //       WITHOUT IT: a project that was deleted would come back in everybody's list.
    //
    //   (d) "p.archived = false" - a finished, archived project belongs to the other
    //       list, findAllArchived below.
    //       WITHOUT IT: years of closed projects would pile up on the main screen and
    //       bury the ones being worked on.
    //
    //   (e) "ORDER BY p.code" - the code is how people name a project out loud, and the
    //       partial unique index uk_projects_code (migration V18) guarantees it is unique
    //       among the live rows, so it is a stable, predictable sort key.
    //       WITHOUT any ORDER BY: PostgreSQL returns the rows in no guaranteed order and
    //       the list could reshuffle between two page loads.
    //
    // WHY NO DISTINCT although there are two joins: both links are @ManyToOne, so a
    // project has at most one director and one chef. A join on a to-one link cannot
    // multiply the rows. DISTINCT would only be needed if a collection were fetched.
    //
    // CAREFUL - this method does NOT apply the scope of ADR-021. It returns the whole
    // portfolio, and ProjectService.findAll() filters the result afterwards with
    // accessibleProjectIds() when the caller does not hold VIEW_ALL_PROJECTS. Sending the
    // result of this method straight to a browser would leak the list of every project of
    // the company.
    //
    // WHO CALLS IT: ProjectService.findAll() (the non-paged list) and AgileDemoSeeder.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false ORDER BY p.code")
    List<Project> findAllActive();

    // WHAT: the mirror of the method above - every project that is archived and not
    //       deleted, same two users loaded, same sort by code.
    // WHY a separate method rather than one method with a boolean argument: the two lists
    //       are two different screens ("Projects" and "Archived projects"), each one
    //       asking for one fixed value. A findAll(boolean archived) would let a caller
    //       pass a variable, and one wrong variable would show archived projects in the
    //       main list. Here the value is written in the query and cannot be changed by a
    //       caller.
    // Everything else - the LEFT JOIN FETCH of the two users, "p.deleted = false", the
    // ORDER BY p.code - is there for exactly the reasons explained on findAllActive
    // above. Only "p.archived = true" differs.
    //
    // WHO CALLS IT: ProjectService.findArchived(), and nothing else.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = true ORDER BY p.code")
    List<Project> findAllArchived();

    // WHAT: reads ONE live project by its id, with its two users already loaded. It gives
    //       back an Optional: a box that either holds the project or is empty.
    // WHY Optional and not the Project itself: it forces the caller to deal with the "not
    //       found" case instead of silently working with null. Every caller writes
    //       .orElseThrow(() -> new NotFoundException(...)), which turns the empty box into
    //       a clean 404.
    //       WITHOUT IT: the method would return null, the next project.getCode() would
    //       throw a NullPointerException, and the user would get a 500 error instead of a
    //       proper "project not found".
    // WHY not the inherited findById(id): findById ignores the soft-delete flag and loads
    //       nothing else. It would let a user open, edit, or attach a new risk to a
    //       project that the team had already deleted.
    // WHY "p.archived" is NOT tested here, on purpose: an archived project must stay
    //       readable - that is the whole difference between archiving and deleting - and
    //       ProjectService.unarchive() has to be able to load it to bring it back.
    // The LEFT JOIN FETCH of the two users matters even more on this method than on the
    // lists, because this is the single entry point used by fifteen services: one extra
    // query per user, on every call of every module, would add up fast.
    //
    // ":id" is a named parameter matched to the Java argument of the same name. No @Param
    // annotation is needed because Spring Boot compiles with the -parameters flag, which
    // keeps the real argument names inside the .class file. And because the value travels
    // to PostgreSQL apart from the query text, it can never be read as SQL: that is what
    // blocks SQL injection.
    //
    // SPEED: no index is written by hand for this read, and none is needed. The column is
    // declared "id BIGSERIAL PRIMARY KEY" (migration V5), so PostgreSQL builds a unique
    // index on it by itself, jumps straight to that single row, and only then checks
    // deleted = false on it.
    //
    // WHO CALLS IT: ProjectService.loadProject, and the loadProject helper of fourteen
    // other services (agile, billing, governance, mission, workload, kpi, team, DI) -
    // fifteen services in total. It is the busiest method of the whole repository
    // layer.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.id = :id AND p.deleted = false")
    Optional<Project> findActiveById(Long id);

    // WHAT: every live project managed by one given user, that user being identified by
    //       his database id (not by his email, unlike the scope query at the end of this
    //       file).
    // WHY it is written on the property path "p.chefProjet.id" and not on a joined alias:
    //       the id of a to-one link is already in the projects table, in the foreign key
    //       column chef_projet_id, so Hibernate reads it without adding a join to users.
    //       WITHOUT that detail: comparing p.chefProjet.email would force a real join on
    //       the users table for a value the project row already holds.
    // NOTE on the LEFT JOIN FETCH here: it is kept so the mapper can read both users, as
    //       on the other methods, but the WHERE clause already demands a chef, so no row
    //       without a chef can come out of this particular query.
    // NOTE on archived projects: this method does not test p.archived, so an archived
    //       project managed by the user is part of the answer.
    //
    // WHO CALLS IT TODAY: no caller in the application - say that plainly rather than
    // guessing. It is the building block for a "my projects" screen. The perimeter of a
    // user is answered elsewhere, by findAccessibleProjectIdsByEmail at the end of this
    // file, which covers more than this one (chef de projet OR team member) and works
    // from the email carried by the token rather than from an id.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.chefProjet.id = :userId AND p.deleted = false")
    List<Project> findActiveByChefProjetId(Long userId);

    // WHAT: answers true when a live project already uses this code, false otherwise.
    // WHY there is no @Query on this one: Spring Data reads the METHOD NAME and writes
    //       the query from it. "existsBy" + "Code" + "And" + "DeletedFalse" becomes
    //       "SELECT count(*) > 0 FROM projects WHERE code = ? AND deleted = false". This
    //       is called a derived query.
    //       The advantage over a hand-written @Query: the name is checked against the
    //       entity at start-up, so renaming the field "code" in Project makes the
    //       application refuse to start, instead of leaving a query that is wrong. The
    //       limit: it only works while the question stays simple enough to be written as
    //       a method name, which is why the heavier reads above use @Query.
    // WHY "DeletedFalse" is part of the name: without it, a code freed by a soft-deleted
    //       project would look taken forever, and the team could never reuse the code of
    //       a cancelled project.
    // WHY exists and not find: nothing is read from the row, only its presence is
    //       tested. Loading the whole project to look at whether it is null would move
    //       far more data for the same answer.
    //
    // THIS CHECK IS NOT THE REAL PROTECTION, AND THAT IS WORTH SAYING OUT LOUD.
    // ProjectService.create() calls this method, then saves. Between the two, another
    // request can insert the same code: two users creating "PRJ-2026-01" at the same
    // instant would both see "free". What really forbids the duplicate is the partial
    // unique index uk_projects_code ON projects(code) WHERE deleted = FALSE, created by
    // migration V18. The database refuses the second insert whatever the application
    // does. The role of this method is to give the normal user a clear message ("code
    // already used") instead of a database error.
    // Why the index is PARTIAL (WHERE deleted = FALSE) and not a plain UNIQUE constraint:
    // V18 replaced the original constraint of V5 precisely because an absolute UNIQUE
    // blocked the reuse of the code of a project that had been soft-deleted.
    //
    // WHO CALLS IT: ProjectService.create (always) and ProjectService.update (only when
    // the code actually changes, otherwise a project would collide with itself), plus
    // DemoDataSeeder and EnterpriseDataSeeder, which use a fixed sentinel code to know
    // whether they have already filled the database and must do nothing.
    boolean existsByCodeAndDeletedFalse(String code);

    // WHAT: reads ONE live project from its business code ("PRJ-2026-01") instead of its
    //       database id, and gives back an Optional for the same reason as
    //       findActiveById: the code may match nothing.
    // WHY it can return at most one row: the partial unique index uk_projects_code
    //       (migration V18) makes the code unique among live projects. On a column
    //       without that index, a derived findBy... returning a single Optional would
    //       throw IncorrectResultSizeDataAccessException the day two rows matched.
    // WHY it is a derived query (no @Query): same reason as the method above - the
    //       question is simple enough to be written as a method name, and Spring Data
    //       checks that name against the entity at start-up.
    // NOTE, and this is the difference with findActiveById: there is no JOIN FETCH here,
    //       so director and chefProjet stay lazy. A caller that needs the name of the
    //       chef would trigger an extra query - and, with "open-in-view: false", only
    //       while the service transaction is still open.
    //
    // WHO CALLS IT TODAY: no caller in the application. It is the natural way in for
    // anything that knows a project by the code printed on a contract rather than by its
    // internal id - an import, or a URL written with the code.
    Optional<Project> findByCodeAndDeletedFalse(String code);

    // WHAT: every live project in one given state (DRAFT, ACTIVE, ON_HOLD, COMPLETED,
    //       CANCELLED - see the enum ProjectStatus and the CHECK constraint chk_status of
    //       migration V5), with the two users loaded.
    // WHY the argument is the enum ProjectStatus and not a String: the compiler then
    //       refuses anything that is not one of the five values.
    //       WITHOUT IT: findActiveByStatus("ACTIF") would compile perfectly and simply
    //       return an empty list at run time, and nobody would understand why the screen
    //       is empty. The column itself is written as text
    //       (@Enumerated(EnumType.STRING)), so Hibernate binds the NAME of the value;
    //       this is also why the database can protect itself with a CHECK on five
    //       readable strings.
    // NOTE: like findActiveByChefProjetId, this one does not test p.archived, so archived
    //       projects with that status are included.
    //
    // SPEED: migration V5 creates the matching partial index
    // "CREATE INDEX idx_projects_status ON projects(status) WHERE deleted = FALSE", which
    // matches both filters of this query exactly.
    //
    // WHO CALLS IT TODAY: no caller in the application. It is the building block for a
    // "projects by state" filter or a dashboard count.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.status = :status AND p.deleted = false")
    List<Project> findActiveByStatus(ProjectStatus status);

    // WHAT: the same list as findAllActive, but one page at a time. Page<Project> carries
    //       the rows of the page AND the total number of matching rows, which is what
    //       lets the screen print "page 2 of 7".
    // WHY a paged version exists next to the full list: a company that has run for years
    //       has hundreds of projects. Sending them all in one response makes the answer
    //       heavy and the table slow to draw. The Angular list asks for 20 rows at a time
    //       (see the frontend project.service.ts).
    //
    // WHY @Query is written here with TWO texts, value and countQuery, instead of one:
    //   * value is the query that reads the rows of the page;
    //   * countQuery is the query that counts the matching rows for the total.
    //   When countQuery is left out, Spring Data builds the count query itself by
    //   rewriting the SELECT clause of the first one. That rewrite is exactly where a paged
    //   query carrying "JOIN FETCH" becomes fragile: a fetch join asks Hibernate to load the
    //   joined user rows INTO the selected project, and when the SELECT is only COUNT(p)
    //   there is no project to load them into. Hibernate then refuses the statement with
    //   "query specified join fetching, but the owner of the fetched association was not
    //   present in the select list", and the projects screen answers 500.
    //   Whether the automatic rewrite manages to strip those fetch joins depends on the
    //   Spring Data version and on the exact text of the query, which is why the Spring Data
    //   manual says to supply countQuery yourself for a paged query like this one. Writing
    //   it by hand removes the doubt instead of betting on the rewrite.
    //   Note that the count query drops the two joins completely: counting projects does
    //   not need their users, and asking PostgreSQL for those joins would only make the
    //   count slower.
    //
    // WHY PAGING AND JOIN FETCH TOGETHER ARE SAFE HERE, which is not always true: both
    // fetched links are @ManyToOne, so one project always gives exactly one row and LIMIT
    // / OFFSET can be applied by the database. If a COLLECTION were fetched instead (say
    // the risks of the project), one project would give several rows, LIMIT would cut in
    // the middle of a project, and Hibernate would fall back to reading everything and
    // paging in memory - fast on a demo, deadly on real data.
    //
    // WHERE THE SORT COMES FROM: there is no ORDER BY in the text. Spring Data appends
    // one from the Sort carried by the Pageable argument. ProjectController declares
    // @PageableDefault(size = 20, sort = "code", direction = ASC), so a request that asks
    // for nothing still gets a stable order. If that default were removed, the pages
    // would overlap and drop rows, because "the first 20 rows" has no meaning without an
    // order.
    //
    // WHO CALLS IT: ProjectService.findAll(Pageable), and only for a user who holds
    // VIEW_ALL_PROJECTS - a user without it goes to findAllActiveByIdIn below instead.
    @Query(value = "SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false",
           countQuery = "SELECT COUNT(p) FROM Project p WHERE p.deleted = false AND p.archived = false")
    Page<Project> findAllActivePaged(Pageable pageable);

    // WHAT: the same page as above, but restricted to a given set of project ids. This is
    //       the scoped list of ADR-021: the ids come from findAccessibleProjectIdsByEmail
    //       at the end of this file.
    // WHY the filtering is done by the database and not in Java: ProjectService could
    //       read the whole page and drop the projects the user may not see - but then a
    //       page of 20 rows could arrive with only 3 rows left, the total count would be
    //       the count of the whole portfolio, and the paging would lie. Passing the
    //       allowed ids into the query keeps the rows AND the total consistent with what
    //       the user is allowed to see.
    //
    // "p.id IN :ids" - one parameter holding the whole list. Hibernate expands it into
    //       "IN (?, ?, ?...)".
    //       WATCH OUT: an empty collection produces "IN ()", which is not valid SQL.
    //       ProjectService guards against it - "if (accessible.isEmpty()) return
    //       Page.empty(pageable);" - so a user with no project at all never reaches this
    //       method. That guard is not decoration: without it, a brand new employee
    //       opening the project list would get a 500 error.
    //
    // WHY @Param("ids") is written here while the methods above have no @Param: it spells
    //       out the link between the argument and ":ids" in the text instead of relying on
    //       the parameter names kept by the compiler. Both work; this one keeps working
    //       even if the build ever stopped passing -parameters.
    //
    // WHY the type is Collection<Long> and not List<Long>: the scope service returns a
    //       Set (ids, no duplicates, no order). Collection is the common parent of both,
    //       so the Set is passed as it is, with no copy.
    //
    // The countQuery repeats the same restriction, for the same reason as above and with
    // one extra consequence: the total shown to the user is the number of projects HE is
    // allowed to see, which is the only total that makes sense to him.
    //
    // WHO CALLS IT: ProjectService.findAll(Pageable), for every user who does not hold
    // VIEW_ALL_PROJECTS.
    @Query(value = "SELECT p FROM Project p LEFT JOIN FETCH p.director LEFT JOIN FETCH p.chefProjet WHERE p.deleted = false AND p.archived = false AND p.id IN :ids",
           countQuery = "SELECT COUNT(p) FROM Project p WHERE p.deleted = false AND p.archived = false AND p.id IN :ids")
    Page<Project> findAllActiveByIdIn(@Param("ids") Collection<Long> ids, Pageable pageable);

    /**
     * Original French note, translated and kept.
     * M-6: one single query replacing the 3 separate calls ProjectScopeService used to
     * make (user lookup + chef-projects + team-assignments).
     * Gives back the ids of every active project where the user is either the chef de
     * projet OR an active team member.
     *
     * <p>WHY M-6 MATTERS: this query runs on EVERY request that touches
     * /api/projects/{id}/**, because ProjectScopeInterceptor calls it through
     * ProjectScopeService.assertCanAccess. Three queries per request instead of one, on
     * the busiest path of the application, is the kind of cost nobody sees in a demo and
     * everybody feels in production. The marker M-6 points at the audit document
     * ENHANCEMENTS_AND_CORRECTIONS.md, where the problem and this fix are recorded.
     *
     * <p>WHY THE PERIMETER IS BUILT FROM RELATIONS AND NOT FROM A ROLE NAME (ADR-001):
     * the query asks "is this person the chef of this project, or assigned to it?", never
     * "is this person a DIRECTEUR?". The global view is a separate permission,
     * VIEW_ALL_PROJECTS, checked in Java by ProjectScopeService.hasAllAccess() before
     * this query is even run - so a holder of that permission never pays for it.
     */
    // HOW THE QUERY IS BUILT, piece by piece:
    //
    //   (a) "SELECT DISTINCT p.id" - only the ids travel back, not the projects. The
    //       caller just needs to answer "is project 42 in the set?". DISTINCT is needed
    //       because the two branches of the OR can both be true for the same project (a
    //       chef de projet who is also listed in the team), and that project would
    //       otherwise appear twice.
    //       WITHOUT the id-only projection: every scoped request would load full project
    //       rows just to throw them away.
    //
    //   (b) "LEFT JOIN p.chefProjet cp" - joins the users table to read the email of the
    //       chef. It must be a LEFT join: a project can have no chef yet (nullable column
    //       chef_projet_id, migration V5).
    //       WITHOUT the LEFT: a plain join would drop every project that has no chef, and
    //       a team member assigned to such a project would be denied access to it - a 403
    //       that nobody could explain.
    //       This is also a plain join, NOT a "join fetch": the user row is only read to
    //       compare an email, never returned.
    //
    //   (c) "p.deleted = false AND p.archived = false" - the perimeter is made of live,
    //       non-archived projects.
    //       Consequence to know before the jury asks: since an archived project is never
    //       in this set, a user who does not hold VIEW_ALL_PROJECTS is refused on an
    //       archived project - both by the interceptor on /api/projects/{id}/** and by
    //       the filtering ProjectService.findArchived() applies to the archived list.
    //
    //   (d) "cp.email = :email OR EXISTS (...)" - the two ways to belong to a project.
    //       The email is used rather than the user id because that is what the security
    //       layer carries: the JWT (JSON Web Token = the signed ticket the browser sends
    //       on every call) holds the email as its subject, and
    //       Authentication.getName() gives it back. Looking the user up by email first,
    //       only to search by id, is precisely the extra query M-6 removed.
    //
    //   (e) "EXISTS (SELECT ta FROM TeamAssignment ta WHERE ta.project = p AND ...)" - a
    //       correlated subquery: for each project of the outer query, it asks the
    //       database whether at least one matching assignment row exists, and stops at
    //       the first one found.
    //       WHY EXISTS and not a second join on team_assignments: a join would produce
    //       one output row per assignment, so a project with 8 members would be repeated
    //       8 times and DISTINCT would have to clean up afterwards. EXISTS answers
    //       yes/no and stops early.
    //       "ta.deleted = false" - an assignment that was removed must not keep its
    //       former member inside the perimeter.
    //       WITHOUT IT: somebody taken off a project would keep reading its risks, its
    //       margins and its invoices for ever.
    //       Note what this condition does NOT test: the end_date of the assignment. An
    //       assignment that is over on paper but not deleted still opens the project.
    //       "Active" here means "not soft-deleted", nothing more.
    //
    //   (f) the query text is cut into several pieces glued with "+". Java 21 also offers
    //       text blocks (three double quotes), which keep the line breaks by themselves;
    //       this file uses plain concatenation, like the rest of the repositories, so the
    //       style stays the same everywhere. Concatenation has one trap: each piece must
    //       end with a space (see "...p.chefProjet cp "), because gluing two pieces without
    //       one would produce "cpWHERE". The @Query texts are parsed when the application
    //       starts, so that typo stops it there and then instead of waiting for a user to
    //       open a project.
    //
    // WHY IT RETURNS Set<Long> AND NOT List<Long>: the caller only ever asks
    // "contains(projectId)?". A Set answers that in one step, says clearly that the ids
    // are unique, and cannot tempt anybody into relying on an order that the query does
    // not guarantee.
    //
    // SPEED: PostgreSQL has the partial indexes it needs for both branches -
    // idx_projects_chef on projects(chef_projet_id) and idx_ta_user_id on
    // team_assignments(user_id), both WHERE deleted = FALSE (migrations V5 and V6).
    //
    // WHO CALLS IT: ProjectScopeService.accessibleProjectIds(email), which is used by
    // ProjectScopeInterceptor (ADR-021, every /api/projects/{id}/** request) and by
    // ProjectService to filter the lists that carry no id in their URL.
    @Query("SELECT DISTINCT p.id FROM Project p " +
           "LEFT JOIN p.chefProjet cp " +
           "WHERE p.deleted = false AND p.archived = false " +
           "AND (cp.email = :email " +
           "OR EXISTS (SELECT ta FROM TeamAssignment ta " +
           "           WHERE ta.project = p AND ta.user.email = :email AND ta.deleted = false))")
    Set<Long> findAccessibleProjectIdsByEmail(@Param("email") String email);
}

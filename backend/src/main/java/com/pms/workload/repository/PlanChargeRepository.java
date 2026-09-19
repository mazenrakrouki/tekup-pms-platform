package com.pms.workload.repository;

import com.pms.workload.entity.PlanCharge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/*
 * =========================================================================
 * WHAT THIS FILE IS
 *   The database access point for one single table: plan_charges. One row of
 *   that table is one line of the monthly staffing plan: "the project manager
 *   expects this person to spend this many days on this project during this
 *   month". "Plan de charge" is the French name of the table - the work that
 *   is PLANNED, as opposed to its twin charges_reelles
 *   (ChargeReelleRepository, right next to this file) which holds the work
 *   that was really done.
 *   A repository is an interface: it only declares the reads and the writes.
 *   It holds no business rule and no permission check - both live in the
 *   service above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular workload screen
 *     -> WorkloadController      /api/projects/{projectId}/plan-charges
 *     -> PlanChargeService       (permission, business rules, transaction)
 *     -> PlanChargeRepository    (THIS FILE)
 *     -> Spring Data JPA / Hibernate -> PostgreSQL table plan_charges
 *   The PlanCharge objects returned here are handed to PlanChargeMapper,
 *   which turns each one into a PlanChargeResponse record (id, projectId,
 *   projectCode, userId, userFullName, year, month, plannedDays) sent to the
 *   browser as JSON. The entity object itself never leaves the server.
 *
 * ONE READER NEVER PASSES THROUGH THE WORKLOAD SCREEN
 *   KpiService.buildKpi() calls findActiveByProjectId() directly, and that one
 *   call is the whole planned side of the KPI screen: the planned budget, the
 *   remaining work (RAF) and the EAC are all built from the rows it gives
 *   back. The two demo loaders (DemoDataSeeder, EnterpriseDataSeeder) also
 *   depend on this repository, but they only use the inherited save().
 *
 * WHY IT EXISTS - what would break if you deleted it
 *   PlanChargeService and KpiService would not compile. Concretely: the
 *   planning half of the workload screen would disappear (no month can be
 *   planned, corrected or cancelled), and the KPI screen would lose its
 *   planned budget, its remaining work and its EAC - it could show what was
 *   spent, but nothing to compare it against. The methods below add exactly
 *   what the ready-made JpaRepository methods cannot do:
 *     1. they hide the rows flagged deleted = true (soft delete),
 *     2. they load the project and the person in the SAME SQL query, because
 *        the mapper reads both right after,
 *     3. they answer an existence question without loading any row,
 *     4. they offer a "this person only" twin of each list, which is how the
 *        own-only data scope is served (BR-062...064).
 *
 * THE TABLE ITSELF (migration V7__schema_workload.sql, audit columns in V19)
 *   period is always the FIRST DAY OF THE MONTH: the service builds it with
 *   LocalDate.of(year, month, 1). So "the same month" is simply "the same
 *   date", which is what lets the plain unique index below do the work.
 *     uk_pc_active   UNIQUE (project_id, user_id, period) WHERE deleted=FALSE
 *     idx_pc_project        (project_id)                  WHERE deleted=FALSE
 *     idx_pc_user           (user_id)                     WHERE deleted=FALSE
 *     chk_pc_days    CHECK  (planned_days > 0 AND planned_days <= 31)
 *   "Partial" (the WHERE deleted = FALSE part) means only the live rows are
 *   covered by the index. That is deliberate: a planning line cancelled by the
 *   project manager can be typed again for the same month, because the old row
 *   is no longer seen by the unique index. The two partial indexes on
 *   project_id and user_id match the filters of the queries below exactly, so
 *   PostgreSQL jumps straight to the rows of one project instead of reading
 *   the whole table.
 *
 * ONE DIFFERENCE WITH ITS TWIN, AND IT SHOWS IN THE QUERIES
 *   A plan line has no approval step: there is no submitted_at, no
 *   validated_at and no validated_by column, because the plan is written by
 *   the manager himself and is true as soon as it is saved. This is why every
 *   query below fetches two links (project, user) where ChargeReelleRepository
 *   fetches three, and why there is no "findValidated..." method here: on the
 *   planned side, every live row counts.
 *
 * SECURITY - the three protections that are NOT written in this file
 *   1. Permission: @PreAuthorize("hasAuthority('VIEW_WORKLOAD')") to read and
 *      'VALIDATE_WORKLOAD' to create, change or delete a planned line - on the
 *      planned side even creating is a manager action, which is why there is
 *      no 'SUBMIT_WORKLOAD' here. They sit on the SERVICE methods, never on
 *      the controller and never here. The code tests a permission code, never
 *      a role name, so an administrator can move a permission from one role to
 *      another while the application is running.
 *   2. Project scope (ADR-021): ProjectScopeInterceptor reads the {id} of the
 *      URL /api/projects/{id}/** and answers 403 when that project is outside
 *      the perimeter of the caller. Holding the permission is NOT enough.
 *   3. Data scope (BR-062...064): inside a project he is allowed to open,
 *      somebody who holds neither VALIDATE_WORKLOAD nor VIEW_ALL_PROJECTS must
 *      see only his own lines. The repository does not decide that; it only
 *      offers the two shapes of the query, and PlanChargeService picks one.
 *   So a repository method is never safe on its own: calling one from a new
 *   place without going through the service would skip all three checks.
 * =========================================================================
 */

/**
 * Spring Data repository for the PlanCharge entity.
 *
 * No class implements this interface anywhere in the project, and that is
 * normal: Spring Data JPA reads the interface at start up and builds the
 * implementation itself (a "proxy" object). It writes the SQL from the @Query
 * text, or from the method NAME when there is no @Query, and hands that object
 * to the services that asked for a PlanChargeRepository. The alternative is to
 * write by hand, for every method, an EntityManager, a createQuery call, the
 * parameter binding and the result list.
 *
 * The two types between &lt; &gt; tell the proxy what to work on:
 *   PlanCharge = the entity, so the table read is plan_charges,
 *   Long       = the type of the @Id field inherited from BaseEntity, so
 *                findById takes a Long.
 * Without those generics findById(1L) would give back an Object, every caller
 * would need a cast, and a wrong cast would only blow up at runtime.
 *
 * JpaRepository also brings in, for free, save(), findAll(), count(),
 * deleteById()... The application uses save() a lot - including to delete,
 * since PlanChargeService.delete() only sets deleted = true and saves - but
 * never deleteById(): erasing a planning row would erase the trace of what had
 * been promised, and the KPI snapshots taken before the change would no longer
 * be explainable.
 */
public interface PlanChargeRepository extends JpaRepository<PlanCharge, Long> {

    // WHAT: every live planning row of ONE project - oldest month first and, inside
    //       a month, people sorted by last name. The project and the person come
    //       back already loaded on each row.
    // WHY : it has two callers, and the second one is the important one.
    //       1. PlanChargeService.findByProject(projectId), the list overload used
    //          when the caller may see the whole team. (The HTTP endpoint itself
    //          uses the paged twin further down.)
    //       2. KpiService.buildKpi(), which needs the WHOLE plan of the project in
    //          one go: it adds up the planned cost month by month, then removes the
    //          months already covered by a validated timesheet to obtain the
    //          remaining work. A paged read would be wrong there - a budget computed
    //          on the first 20 rows only would simply be false.
    //       Four things are packed into this single line of JPQL. JPQL looks like
    //       SQL but is written on the Java classes: "PlanCharge pc" is the entity
    //       name, not the table name, and Hibernate translates it into real SQL.
    //
    //   (a) "pc.project.id = :projectId" - a plan is always read inside one project.
    //       The filter is written on the property path pc.project.id, which
    //       Hibernate resolves to the foreign key column project_id: it does NOT add
    //       another join to the projects table.
    //       WITHOUT IT: the planning screen of project 12 would show the staffing of
    //       the whole company, and KpiService would add the planned budget of every
    //       project into the budget of one.
    //
    //   (b) "pc.deleted = false" - cancelling a planning line never erases the row,
    //       it only sets the boolean column deleted to true (the field comes from
    //       BaseEntity, the parent class of PlanCharge). This is a "soft delete":
    //       the row stays in the database for the audit trail, so every read has to
    //       filter it out itself.
    //       WITHOUT IT: a month the manager cancelled would still be counted in the
    //       planned budget and in the remaining work, so the KPI screen would show
    //       work nobody intends to do any more.
    //
    //   (c) "JOIN FETCH pc.project JOIN FETCH pc.user" - both links are mapped
    //       FetchType.LAZY on the entity, so Hibernate would normally leave them as
    //       empty placeholders and run one extra SELECT the first time each one is
    //       read. PlanChargeMapper reads project.id, project.code, user.id and
    //       user.getFullName() on EVERY row, and KpiService reads the user of every
    //       row to find that person's daily rate.
    //       WITHOUT IT: a plan of 120 rows costs 1 query plus up to 240 more, two
    //       per row - the classic "N+1 select" problem, paid every time the KPI
    //       screen is opened. And worse: application.yml sets "open-in-view: false",
    //       which means a lazy link can only be loaded while the service transaction
    //       is still open; any mapping done after the service returned fails with
    //       LazyInitializationException, a 500 error on a screen that looked fine.
    //       These are plain JOINs, not LEFT JOINs, and that is correct here:
    //       project_id and user_id are both NOT NULL in the table (V7), so every
    //       planning row always has a project and a person, and no row can be
    //       silently dropped by the join.
    //
    //   (d) "ORDER BY pc.period, pc.user.lastName" - the screen is read month by
    //       month, and inside a month names are easier to scan in alphabetical
    //       order. The sort is done by PostgreSQL, not in Java. pc.user.lastName
    //       reuses the join already made by the JOIN FETCH above; it does not add a
    //       second one.
    //       WITHOUT IT: the database is free to give the rows back in any order, and
    //       the same screen opened twice could show the months shuffled differently.
    //
    // ":projectId" is a named parameter matched to the Java argument of the same
    // name. No @Param annotation is needed because the build compiles with the
    // -parameters flag, inherited from the parent POM spring-boot-starter-parent,
    // which keeps the real argument names inside the .class file. WITHOUT that flag
    // the compiler names the argument "arg0", Spring Data can no longer match it to
    // ":projectId", and the application refuses to start. Why a parameter instead of
    // gluing the value into the query text: the value travels to PostgreSQL apart
    // from the query, so it can never be read as SQL. That is what blocks SQL
    // injection.
    @Query("SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.deleted = false ORDER BY pc.period, pc.user.lastName")
    List<PlanCharge> findActiveByProjectId(Long projectId);

    // WHAT: ONE live planning row read by its own id, with its project and its
    //       person already loaded. Optional.empty() when the id does not exist or
    //       when the row was soft-deleted.
    // WHY : this is the entry point of PlanChargeService.loadPlanCharge(), which
    //       update() and delete() both start with. The ingredients are the same as
    //       above, with the primary key as the filter instead of the project.
    //       "pc.deleted = false" matters even more here than in a list: WITHOUT IT a
    //       line cancelled last month could still be edited through
    //       PUT /api/projects/{id}/plan-charges/{id}, and saving it would bring back
    //       into the planned budget a month nobody intends to work.
    //       The JOIN FETCH pair is kept because update() gives a mapped response
    //       back to the browser, so the mapper needs the project and the person
    //       right after the service transaction closes.
    //       Note that this method does NOT check which project the row belongs to;
    //       the service does that itself with
    //       "if (!pc.getProject().getId().equals(projectId)) throw NotFound", so that
    //       asking for a row of project 9 through the URL of project 3 answers 404
    //       instead of quietly working. That check is what keeps the ADR-021
    //       perimeter honest: the interceptor only sees the project number written
    //       in the URL, so the row must really belong to that project.
    //
    // Optional<...> is the return type on purpose: it forces the caller to say what
    // happens when nothing is found. PlanChargeService writes
    // .orElseThrow(() -> new NotFoundException(...)), which GlobalExceptionHandler
    // turns into a clean 404.
    // WITHOUT IT (returning PlanCharge and null): a forgotten null check would
    // surface as a NullPointerException, that is a 500 "server error" instead of an
    // honest "this planning line does not exist".
    @Query("SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.id = :id AND pc.deleted = false")
    Optional<PlanCharge> findActiveById(Long id);

    // WHAT: answers true or false to the question "does this person already have a
    //       live planning row on this project for this month?". It gives back a
    //       boolean, not a row.
    // WHY : PlanChargeService.create() asks this before inserting and throws
    //       IllegalArgumentException when the answer is true; GlobalExceptionHandler
    //       turns that into 409 Conflict with a readable message. It protects the
    //       rule "one planned line per person, per project, per month".
    //       WITHOUT IT: the same month could be planned twice for the same person,
    //       the planned budget of the project would count those days twice, and the
    //       remaining work shown on the KPI screen would be inflated for ever.
    //
    // There is no @Query here: Spring Data builds the SQL from the method NAME. It
    // reads the name piece by piece - existsBy / ProjectId / And / UserId / And /
    // Period / And / DeletedFalse - and produces "SELECT ... WHERE project_id = ?
    // AND user_id = ? AND period = ? AND deleted = false" limited to one row. The
    // name IS the query, so renaming this method changes the SQL: that is the trap
    // of derived queries, and the reason the name is so long.
    // "exists" rather than "find": the answer is one boolean, so no row is loaded
    // into memory and no entity has to be built.
    // "DeletedFalse" is not decoration: it matches the partial unique index of the
    // table, uk_pc_active ... WHERE deleted = FALSE. WITHOUT that piece of the name,
    // a month that had been cancelled earlier would still count as taken, and the
    // manager could never plan that month again.
    //
    // This check is the friendly guarantee, not the real one. Between the check and
    // the INSERT, a second request can slip in. The last word belongs to the unique
    // index uk_pc_active: PostgreSQL refuses the second insert, and
    // GlobalExceptionHandler catches the DataIntegrityViolationException and also
    // answers 409. Two layers on purpose: a clear message in the normal case, a hard
    // guarantee when two managers click "save" at the very same moment.
    //
    // The LocalDate argument is always the first day of a month, because the service
    // builds it with LocalDate.of(year, month, 1). That is what makes the plain
    // equality test "period = ?" mean "the same month".
    boolean existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(Long projectId, Long userId, LocalDate period);

    // WHAT: the same list as findActiveByProjectId, but one page at a time. A Page
    //       carries the rows of the asked page AND the total number of rows, which
    //       is what the pager of the screen needs to display "page 2 of 7".
    // WHY : this is the method the HTTP endpoint really uses -
    //       GET /api/projects/{projectId}/plan-charges, through
    //       PlanChargeService.findByProject(projectId, pageable). A project with 10
    //       people over 12 months already holds about 120 planning rows, and a long
    //       project far more; sending them all in one response would make the screen
    //       slow for nothing, since only 20 are displayed. KpiService keeps using
    //       the unpaged version above, because a budget must be computed on all the
    //       rows, never on one page.
    //
    //   (a) Pageable carries the page number, the page size and the sort. Spring
    //       Data adds LIMIT and OFFSET to the SQL and appends the ORDER BY of that
    //       sort. This is why the query has NO "ORDER BY" of its own, unlike the
    //       list version above: WorkloadController sets the default with
    //       @PageableDefault(size = 20, sort = "period", direction = DESC) - newest
    //       month first - and the user can change it from the screen. A fixed
    //       ORDER BY written here would fight with that choice.
    //
    //   (b) countQuery = "SELECT COUNT(pc) ..." - a Page must also answer "how many
    //       rows in total?". There are two reasons to write that second query by
    //       hand instead of letting Spring Data derive it from the first one. First,
    //       a derived count would keep the JOIN FETCH, which is not allowed in a
    //       count query - Hibernate then fails with "query specified join fetching,
    //       but the owner of the fetched association was not present in the select
    //       list". Second, counting needs neither the project nor the person, so the
    //       hand-written count skips the two joins and is much cheaper.
    //       The WHERE of the count is exactly the WHERE of the main query - same
    //       project filter, same soft-delete filter. WITHOUT that exact match the
    //       pager would announce a number of pages that does not exist, and the last
    //       pages would come back empty.
    //
    //   (c) JOIN FETCH together with pagination is safe HERE because project and
    //       user are both @ManyToOne links: one entity gives exactly one SQL row, so
    //       LIMIT and OFFSET can stay inside the SQL. With a @OneToMany collection
    //       fetch, one entity would spread over several SQL rows and Hibernate would
    //       have to read everything and cut the page in memory (its HHH000104
    //       warning) - which would defeat the whole point of paging.
    @Query(value = "SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.deleted = false",
           countQuery = "SELECT COUNT(pc) FROM PlanCharge pc WHERE pc.project.id = :projectId AND pc.deleted = false")
    Page<PlanCharge> findActiveByProjectIdPaged(Long projectId, Pageable pageable);

    // ---- "Own only" data scope (BR-062...064): the reads of somebody who has no
    //      wide view ----
    // (This is the translation of the French note that was written here.)
    // PlanChargeService.canSeeAllWorkload() answers true when the caller holds
    // VALIDATE_WORKLOAD (the project manager, who writes the plan) or
    // VIEW_ALL_PROJECTS (the director, who supervises the portfolio). For everybody
    // else the service calls the two methods below with the id of the caller, so a
    // developer working on the project reads the days planned for himself and
    // nothing else. The test is made on a capability, never on the name of a role
    // (ADR-001).
    // WITHOUT these two queries - and this is exactly the hole written down in
    // docs/AUTHORIZATION_MATRIX.md section 5.3.2 - a developer assigned to a project
    // reads the whole staffing plan of the team, which says who is loaded, who is
    // idle and who the manager is counting on for the months to come.
    // Why a second pair of queries rather than filtering in Java after the read: a
    // Java filter would still load the rows of the whole team into memory first,
    // and, on a paged read, a page of 20 rows would arrive already emptied of the
    // other people - the pager would then offer pages that show nothing. Filtering
    // in SQL keeps both the page and the total count honest.
    // One safety net lives on the caller side: when the current user cannot be
    // resolved, PlanChargeService passes -1 as userId. No row can have user_id = -1,
    // so the query gives back nothing instead of giving back everything.

    // WHAT: every live planning row of ONE person on ONE project, oldest month
    //       first, with project and person already loaded.
    // WHY : it is the own-only twin of findActiveByProjectId. Same ingredients, plus
    //       the filter that is the whole point - "pc.user.id = :userId", which
    //       Hibernate resolves to the foreign key column user_id, so it adds no
    //       extra join to the users table.
    //       The ORDER BY drops the last name used in the list version: every row
    //       belongs to the same person, so only the month matters.
    @Query("SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.user.id = :userId AND pc.deleted = false ORDER BY pc.period")
    List<PlanCharge> findActiveByProjectIdAndUserId(Long projectId, Long userId);

    // WHAT: the paged own-only twin of findActiveByProjectIdPaged.
    // WHY : this is the one a developer really hits, because the endpoint is paged.
    //       Everything written above about Pageable, about the hand-written
    //       countQuery and about JOIN FETCH with @ManyToOne links applies here too,
    //       with one extra point that matters a lot: the count query carries the
    //       SAME "pc.user.id = :userId" filter as the main query.
    //       WITHOUT that, the total would be the number of rows of the whole team
    //       while the rows shown are only his - the pager would offer pages that
    //       come back empty, and the developer would still learn how many months
    //       have been planned for his colleagues, which is the very thing the
    //       filter is there to hide.
    @Query(value = "SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.user.id = :userId AND pc.deleted = false",
           countQuery = "SELECT COUNT(pc) FROM PlanCharge pc WHERE pc.project.id = :projectId AND pc.user.id = :userId AND pc.deleted = false")
    Page<PlanCharge> findActiveByProjectIdAndUserIdPaged(Long projectId, Long userId, Pageable pageable);
}

package com.pms.workload.repository;

import com.pms.workload.entity.ChargeReelle;
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
 *   The database access point for one single table: charges_reelles. One row
 *   of that table is one timesheet line: "this person really worked this many
 *   days on this project during this month". "Charge reelle" is the French
 *   name of the table - the work that was REALLY done, as opposed to its twin
 *   plan_charges (PlanChargeRepository, right next to this file) which holds
 *   the work that was PLANNED.
 *   A repository is an interface: it only declares the reads and the writes.
 *   It holds no business rule and no permission check - both live in the
 *   service above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular workload screen
 *     -> WorkloadController      /api/projects/{projectId}/charges-reelles
 *     -> ChargeReelleService     (permission, business rules, transaction)
 *     -> ChargeReelleRepository  (THIS FILE)
 *     -> Spring Data JPA / Hibernate -> PostgreSQL table charges_reelles
 *   The ChargeReelle objects returned here are handed to ChargeReelleMapper,
 *   which turns each one into a ChargeReelleResponse record (id, projectId,
 *   projectCode, userId, userFullName, year, month, actualDays, submittedAt,
 *   validatedAt, validatedById, validatedByName) sent to the browser as JSON.
 *   The entity object itself never leaves the server.
 *
 * ONE READER NEVER PASSES THROUGH THE WORKLOAD SCREEN
 *   KpiService.buildKpi() calls findValidatedByProjectId() directly, to know
 *   how much of the budget of a project has really been consumed. That single
 *   method is the reason the KPI screen can show a consumed cost, an EAC and a
 *   margin. The two demo loaders (DemoDataSeeder, EnterpriseDataSeeder) also
 *   depend on this repository, but they only use the inherited save().
 *
 * WHY IT EXISTS - what would break if you deleted it
 *   ChargeReelleService and KpiService would not compile. Concretely: the
 *   timesheet half of the workload screen would disappear (no submit, no
 *   correction, no approval, no list), and the KPI screen would have no
 *   consumed cost at all, so plan-versus-actual, EAC and margin would have
 *   nothing to compute. The methods below add exactly what the ready-made
 *   JpaRepository methods cannot do:
 *     1. they hide the rows flagged deleted = true (soft delete),
 *     2. they load the project, the person and the validator in the SAME SQL
 *        query, because the mapper reads all three right after,
 *     3. they answer an existence question without loading any row,
 *     4. they offer a "this person only" twin of each list, which is how the
 *        own-only data scope is served (BR-062...064).
 *
 * THE TABLE ITSELF (migration V7__schema_workload.sql, audit columns in V19)
 *   period is always the FIRST DAY OF THE MONTH: the service builds it with
 *   LocalDate.of(year, month, 1). So "the same month" is simply "the same
 *   date", which is what lets the plain unique index below do the work.
 *     uk_cr_active   UNIQUE (project_id, user_id, period) WHERE deleted=FALSE
 *     idx_cr_project        (project_id)                  WHERE deleted=FALSE
 *     idx_cr_user           (user_id)                     WHERE deleted=FALSE
 *     chk_cr_days    CHECK  (actual_days >= 0 AND actual_days <= 31)
 *   "Partial" (the WHERE deleted = FALSE part) means only the live rows are
 *   covered by the index. That is deliberate: a timesheet line cancelled by
 *   the project manager can be typed again for the same month, because the old
 *   row is no longer seen by the unique index. The two partial indexes on
 *   project_id and user_id match the filters of the queries below exactly, so
 *   PostgreSQL jumps straight to the rows of one project instead of reading
 *   the whole table.
 *
 * SECURITY - the three protections that are NOT written in this file
 *   1. Permission: @PreAuthorize("hasAuthority('VIEW_WORKLOAD')") to read,
 *      'SUBMIT_WORKLOAD' to declare or correct days, 'VALIDATE_WORKLOAD' to
 *      approve or delete. They sit on the SERVICE methods, never on the
 *      controller and never here. The code tests a permission code, never a
 *      role name, so an administrator can move a permission from one role to
 *      another while the application is running.
 *   2. Project scope (ADR-021): ProjectScopeInterceptor reads the {id} of the
 *      URL /api/projects/{id}/** and answers 403 when that project is outside
 *      the perimeter of the caller. Holding the permission is NOT enough.
 *   3. Data scope (BR-062...064): inside a project he is allowed to open,
 *      somebody who holds neither VALIDATE_WORKLOAD nor VIEW_ALL_PROJECTS must
 *      see only his own lines. The repository does not decide that; it only
 *      offers the two shapes of the query, and ChargeReelleService picks one.
 *   So a repository method is never safe on its own: calling one from a new
 *   place without going through the service would skip all three checks.
 * =========================================================================
 */

/**
 * Spring Data repository for the ChargeReelle entity.
 *
 * No class implements this interface anywhere in the project, and that is
 * normal: Spring Data JPA reads the interface at start up and builds the
 * implementation itself (a "proxy" object). It writes the SQL from the @Query
 * text, or from the method NAME when there is no @Query, and hands that object
 * to the services that asked for a ChargeReelleRepository. The alternative is
 * to write by hand, for every method, an EntityManager, a createQuery call,
 * the parameter binding and the result list.
 *
 * The two types between &lt; &gt; tell the proxy what to work on:
 *   ChargeReelle = the entity, so the table read is charges_reelles,
 *   Long         = the type of the @Id field inherited from BaseEntity, so
 *                  findById takes a Long.
 * Without those generics findById(1L) would give back an Object, every caller
 * would need a cast, and a wrong cast would only blow up at runtime.
 *
 * JpaRepository also brings in, for free, save(), findAll(), count(),
 * deleteById()... The application uses save() a lot - including to delete,
 * since ChargeReelleService.delete() only sets deleted = true and saves - but
 * never deleteById(): erasing a timesheet row would erase the proof of the
 * days that were counted in the cost of a project.
 */
public interface ChargeReelleRepository extends JpaRepository<ChargeReelle, Long> {

    // WHAT: every live timesheet row of ONE project - oldest month first and, inside
    //       a month, people sorted by last name. The project, the person and the
    //       validator come back already loaded on each row.
    // WHY : it feeds ChargeReelleService.findByProject(projectId), the list overload
    //       used when the caller may see the whole team. (The HTTP endpoint itself
    //       uses the paged twin further down; this overload exists for a caller that
    //       wants the complete list in one go.)
    //       Five things are packed into this single line of JPQL. JPQL looks like
    //       SQL but is written on the Java classes: "ChargeReelle cr" is the entity
    //       name, not the table name, and Hibernate translates it into real SQL.
    //
    //   (a) "cr.project.id = :projectId" - the list is always read inside one
    //       project. The filter is written on the property path cr.project.id, which
    //       Hibernate resolves to the foreign key column project_id: it does NOT add
    //       another join to the projects table.
    //       WITHOUT IT: the workload screen of project 12 would list the timesheets
    //       of the whole company.
    //
    //   (b) "cr.deleted = false" - deleting a timesheet line never erases the row,
    //       it only sets the boolean column deleted to true (the field comes from
    //       BaseEntity, the parent class of ChargeReelle). This is a "soft delete":
    //       the row stays in the database for the audit trail, so every read has to
    //       filter it out itself.
    //       WITHOUT IT: days cancelled by the project manager would reappear on the
    //       screen, and the person would believe they are still counted.
    //
    //   (c) "JOIN FETCH cr.project JOIN FETCH cr.user" - both links are mapped
    //       FetchType.LAZY on the entity, so Hibernate would normally leave them as
    //       empty placeholders and run one extra SELECT the first time each one is
    //       read. ChargeReelleMapper reads project.id, project.code, user.id and
    //       user.getFullName() on EVERY row, so JOIN FETCH brings those rows back in
    //       the same statement.
    //       WITHOUT IT: a list of 60 rows costs 1 query plus up to 120 more, two per
    //       row - the classic "N+1 select" problem. And worse: application.yml sets
    //       "open-in-view: false", which means a lazy link can only be loaded while
    //       the service transaction is still open; any mapping done after the
    //       service returned fails with LazyInitializationException, a 500 error on
    //       a screen that looked perfectly fine.
    //       These two are plain JOINs, not LEFT JOINs, and that is correct here:
    //       project_id and user_id are both NOT NULL in the table (V7), so every
    //       timesheet line always has a project and a person, and no row can be
    //       silently dropped by the join.
    //
    //   (d) "LEFT JOIN FETCH cr.validatedBy" - LEFT this time, on purpose. The
    //       column validated_by is nullable: a line that has been submitted but not
    //       yet approved has no validator. LEFT keeps such a row and simply leaves
    //       validatedBy null.
    //       WITHOUT THE WORD "LEFT": every row waiting for approval would vanish
    //       from the list - the developer would not see the days he just submitted,
    //       and the project manager would have nothing left to approve.
    //       The fetch itself is needed because the mapper reads validatedById and
    //       validatedByName to fill the "validated by" column of the screen.
    //
    //   (e) "ORDER BY cr.period, cr.user.lastName" - the screen is read month by
    //       month, and inside a month names are easier to scan in alphabetical
    //       order. The sort is done by PostgreSQL, not in Java. cr.user.lastName
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
    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.deleted = false ORDER BY cr.period, cr.user.lastName")
    List<ChargeReelle> findActiveByProjectId(Long projectId);

    // WHAT: ONE live timesheet row read by its own id, with its project, its person
    //       and its validator already loaded. Optional.empty() when the id does not
    //       exist or when the row was soft-deleted.
    // WHY : this is the entry point of ChargeReelleService.loadChargeReelle(), which
    //       update(), validate() and delete() all start with. The ingredients are
    //       the same as above, with the primary key as the filter instead of the
    //       project.
    //       "cr.deleted = false" matters even more here than in a list: WITHOUT IT a
    //       line deleted last month could still be approved through
    //       PATCH /api/projects/{id}/charges-reelles/{id}/validate, and those days
    //       would silently come back into the consumed budget of the project.
    //       The JOIN FETCH set is kept because all three write paths give a mapped
    //       response back to the browser, so the mapper needs the project, the
    //       person and the validator right after the service transaction closes.
    //       Note that this method does NOT check which project the row belongs to;
    //       the service does that itself, line by line, with
    //       "if (!cr.getProject().getId().equals(projectId)) throw NotFound", so that
    //       asking for a row of project 9 through the URL of project 3 answers 404
    //       instead of quietly working.
    //
    // Optional<...> is the return type on purpose: it forces the caller to say what
    // happens when nothing is found. ChargeReelleService writes
    // .orElseThrow(() -> new NotFoundException(...)), which GlobalExceptionHandler
    // turns into a clean 404.
    // WITHOUT IT (returning ChargeReelle and null): a forgotten null check would
    // surface as a NullPointerException, that is a 500 "server error" instead of an
    // honest "this line does not exist".
    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.id = :id AND cr.deleted = false")
    Optional<ChargeReelle> findActiveById(Long id);

    // WHAT: answers true or false to the question "does this person already have a
    //       live timesheet row on this project for this month?". It gives back a
    //       boolean, not a row.
    // WHY : ChargeReelleService.submit() asks this before inserting and throws
    //       IllegalArgumentException when the answer is true; GlobalExceptionHandler
    //       turns that into 409 Conflict with a readable message. It protects the
    //       rule "one line per person, per project, per month".
    //       WITHOUT IT: the same month could be declared twice, those days would be
    //       counted twice in the consumed budget of the project, and the margin
    //       shown to the management would be wrong without anybody seeing why.
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
    // table, uk_cr_active ... WHERE deleted = FALSE. WITHOUT that piece of the name,
    // a month that had been deleted earlier would still count as taken, and the
    // person could never declare that month again.
    //
    // This check is the friendly guarantee, not the real one. Between the check and
    // the INSERT, a second request can slip in. The last word belongs to the unique
    // index uk_cr_active: PostgreSQL refuses the second insert, and
    // GlobalExceptionHandler catches the DataIntegrityViolationException and also
    // answers 409. Two layers on purpose: a clear message in the normal case, a hard
    // guarantee when two requests arrive at the very same moment.
    //
    // The LocalDate argument is always the first day of a month, because the service
    // builds it with LocalDate.of(year, month, 1). That is what makes the plain
    // equality test "period = ?" mean "the same month".
    boolean existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(Long projectId, Long userId, LocalDate period);

    // WHAT: the same list as findActiveByProjectId, but one page at a time. A Page
    //       carries the rows of the asked page AND the total number of rows, which
    //       is what the pager of the screen needs to display "page 2 of 7".
    // WHY : this is the method the HTTP endpoint really uses -
    //       GET /api/projects/{projectId}/charges-reelles, through
    //       ChargeReelleService.findByProject(projectId, pageable). A project with
    //       10 people over 12 months already holds about 120 timesheet rows, and a
    //       long project far more; sending them all in one response would make the
    //       screen slow for nothing, since only 20 are displayed.
    //
    //   (a) Pageable carries the page number, the page size and the sort. Spring
    //       Data adds LIMIT and OFFSET to the SQL and appends the ORDER BY of that
    //       sort. This is why the query has NO "ORDER BY" of its own, unlike the
    //       list version above: WorkloadController sets the default with
    //       @PageableDefault(size = 20, sort = "period", direction = DESC) - newest
    //       month first - and the user can change it from the screen. A fixed
    //       ORDER BY written here would fight with that choice.
    //
    //   (b) countQuery = "SELECT COUNT(cr) ..." - a Page must also answer "how many
    //       rows in total?". There are two reasons to write that second query by
    //       hand instead of letting Spring Data derive it from the first one. First,
    //       a derived count would keep the JOIN FETCH, which is not allowed in a
    //       count query - Hibernate then fails with "query specified join fetching,
    //       but the owner of the fetched association was not present in the select
    //       list". Second, counting needs neither the project, nor the person, nor
    //       the validator, so the hand-written count skips the three joins and is
    //       much cheaper.
    //       The WHERE of the count is exactly the WHERE of the main query - same
    //       project filter, same soft-delete filter. WITHOUT that exact match the
    //       pager would announce a number of pages that does not exist, and the last
    //       pages would come back empty.
    //
    //   (c) JOIN FETCH together with pagination is safe HERE because project, user
    //       and validatedBy are all @ManyToOne links: one entity gives exactly one
    //       SQL row, so LIMIT and OFFSET can stay inside the SQL. With a @OneToMany
    //       collection fetch, one entity would spread over several SQL rows and
    //       Hibernate would have to read everything and cut the page in memory (its
    //       HHH000104 warning) - which would defeat the whole point of paging.
    @Query(value = "SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.deleted = false",
           countQuery = "SELECT COUNT(cr) FROM ChargeReelle cr WHERE cr.project.id = :projectId AND cr.deleted = false")
    Page<ChargeReelle> findActiveByProjectIdPaged(Long projectId, Pageable pageable);

    // WHAT: only the timesheet rows of a project that have been APPROVED (their
    //       validated_at column is filled), with the person already loaded.
    // WHY : this is the cost input of the whole KPI engine. KpiService.buildKpi()
    //       reads it to compute the consumed budget, the EAC, the margin and the
    //       consumed man-days. "Validated" and not simply "active" is a business
    //       decision: a row that was submitted but not approved is a claim, not a
    //       fact.
    //       WITHOUT that filter a developer could move the consumed budget and the
    //       margin of a project on his own, just by typing a number - the approval
    //       by the project manager is what turns a declared day into a cost.
    //
    //   (a) "cr.validatedAt IS NOT NULL" - validated_at is nullable, and a null
    //       simply means "waiting for approval". It is the same test the entity
    //       exposes as ChargeReelle.isValidated().
    //
    //   (b) "cr.deleted = false" - a row cancelled by the project manager must stop
    //       weighing on the cost. WITHOUT IT a deleted month would keep inflating
    //       the consumed budget of the project for ever.
    //
    //   (c) "JOIN FETCH cr.user" and nothing else - on purpose, this is the only
    //       query of the file that does not fetch the project. KpiService reads
    //       cr.getUser() (to find the daily rate and the name of that person),
    //       cr.getActualDays() and cr.getPeriod(); it never reads cr.getProject(),
    //       because it already holds the Project object it was called with. Fetching
    //       the project here would join a table for nothing, on every row.
    //       The user fetch itself IS needed: WITHOUT IT, Hibernate would fire one
    //       extra SELECT per row to read the user, so a project with 240 validated
    //       rows would cost 240 extra round trips every time the KPI screen is
    //       opened or a snapshot is taken.
    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.user WHERE cr.project.id = :projectId AND cr.validatedAt IS NOT NULL AND cr.deleted = false")
    List<ChargeReelle> findValidatedByProjectId(Long projectId);

    // ---- "Own only" data scope (BR-062...064): the reads of somebody who has no
    //      wide view ----
    // (This is the translation of the French note that was written here.)
    // ChargeReelleService.canSeeAllWorkload() answers true when the caller holds
    // VALIDATE_WORKLOAD (the project manager, who has to approve the days) or
    // VIEW_ALL_PROJECTS (the director, who supervises the portfolio). For everybody
    // else the service calls the two methods below with the id of the caller, so a
    // developer working on the project reads his own rows and nothing else. The test
    // is made on a capability, never on the name of a role (ADR-001).
    // WITHOUT these two queries - and this is exactly the hole written down in
    // docs/AUTHORIZATION_MATRIX.md section 5.3.2 - a developer assigned to a project
    // reads the timesheets of all his colleagues, and from the days of each person
    // one can guess who is loaded, who is idle, and roughly what each one costs.
    // Why a second pair of queries rather than filtering in Java after the read: a
    // Java filter would still load the rows of the whole team into memory first,
    // and, on a paged read, a page of 20 rows would arrive already emptied of the
    // other people - the pager would then offer pages that show nothing. Filtering
    // in SQL keeps both the page and the total count honest.
    // One safety net lives on the caller side: when the current user cannot be
    // resolved, ChargeReelleService passes -1 as userId. No row can have
    // user_id = -1, so the query gives back nothing instead of giving back
    // everything.

    // WHAT: every live timesheet row of ONE person on ONE project, oldest month
    //       first, with project, person and validator already loaded.
    // WHY : it is the own-only twin of findActiveByProjectId. Same ingredients, plus
    //       the filter that is the whole point - "cr.user.id = :userId", which
    //       Hibernate resolves to the foreign key column user_id, so it adds no
    //       extra join to the users table.
    //       The ORDER BY drops the last name used in the list version: every row
    //       belongs to the same person, so only the month matters.
    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.user.id = :userId AND cr.deleted = false ORDER BY cr.period")
    List<ChargeReelle> findActiveByProjectIdAndUserId(Long projectId, Long userId);

    // WHAT: the paged own-only twin of findActiveByProjectIdPaged.
    // WHY : this is the one a developer really hits, because the endpoint is paged.
    //       Everything written above about Pageable, about the hand-written
    //       countQuery and about JOIN FETCH with @ManyToOne links applies here too,
    //       with one extra point that matters a lot: the count query carries the
    //       SAME "cr.user.id = :userId" filter as the main query.
    //       WITHOUT that, the total would be the number of rows of the whole team
    //       while the rows shown are only his - the pager would offer pages that
    //       come back empty, and the developer would still learn how many rows his
    //       colleagues have, which is the very thing the filter is there to hide.
    @Query(value = "SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.user.id = :userId AND cr.deleted = false",
           countQuery = "SELECT COUNT(cr) FROM ChargeReelle cr WHERE cr.project.id = :projectId AND cr.user.id = :userId AND cr.deleted = false")
    Page<ChargeReelle> findActiveByProjectIdAndUserIdPaged(Long projectId, Long userId, Pageable pageable);
}

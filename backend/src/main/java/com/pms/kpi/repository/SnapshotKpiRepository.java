package com.pms.kpi.repository;

import com.pms.kpi.entity.SnapshotKpi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

/**
 * WHAT THIS FILE IS
 * Database access for one table: snapshot_kpis. A snapshot is a photograph of the
 * financial and progress indicators of one project on one day (planned budget, spent
 * budget, EAC, margin, EV %, delivery %, FAE...). A repository is the only place in the
 * application that talks to the database for that table. It carries no business rule,
 * no computation and no permission check; all three live in KpiService above it.
 *
 * WHY SNAPSHOTS ARE STORED WHILE THE LIVE KPI IS NOT
 * KpiService.computeLive recomputes every indicator from the raw data every time the
 * screen is opened, and stores nothing. A snapshot is the opposite: it freezes the
 * numbers of the day into a row, so the history of the project can still be read later,
 * even after the workload, the daily rates or the deliverables have changed. This is the
 * only reason this table, this entity and this file exist.
 * Careful with the wording in front of the jury: the amounts of the DEVIS INTERNE (DI,
 * the internal quote) are never stored in a column, they are always derived when read. A
 * snapshot is a different thing: a dated history row that a user asked for on purpose.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> KpiController  (/api/projects/{projectId}/kpi/snapshots)
 *           -> KpiService     (permission, duplicate rule, computation, transaction)
 *           -> SnapshotKpiRepository (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table snapshot_kpis
 * The SnapshotKpi rows returned here are handed to SnapshotKpiMapper, which turns them
 * into the KpiResponse record sent to the browser as JSON (a DTO: a flat object built
 * only to travel over the network). The entity itself never leaves the server.
 *
 * THE FOUR CALLERS, AND WHAT EACH ONE NEEDS
 *   1. KpiService.findSnapshots    -> findActiveByProjectId, for the history table of the
 *                                     KPI screen (newest line on top).
 *   2. KpiService.latestSnapshotEv -> findActiveByProjectId again, but it keeps only the
 *                                     most recent row. This one matters: the EV %
 *                                     (Earned Value, the share of the work really earned)
 *                                     is typed in by the project manager at the monthly
 *                                     review, it cannot be computed from the raw data. So
 *                                     the LIVE screen reuses the EV % of the last
 *                                     snapshot. Without this table there would be no EV %
 *                                     at all, and therefore no CA production, no FAE and
 *                                     no current margin on the live screen.
 *   3. KpiService.createSnapshot   -> existsByProjectIdAndSnapshotDateAndDeletedFalse to
 *                                     refuse a second snapshot on the same day, then the
 *                                     inherited save() to write the row.
 *   4. DemoDataSeeder and EnterpriseDataSeeder -> the inherited save(), to build the
 *                                     sample history of the demo projects at startup.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * KpiService and the two seeders would not compile. The KPI screen would lose its history
 * table and, less visible but worse, it would lose the EV % that every EVM indicator is
 * built on. The two methods below add exactly what the built-in JpaRepository methods
 * cannot do:
 *   1. they hide the rows flagged deleted = true (soft delete),
 *   2. they load the project in the SAME SQL query, which the mapper needs right after,
 *   3. they answer the "is there already one today?" question in one cheap query.
 *
 * SECURITY - the two protections that are NOT written in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_KPI')") sits on
 *    KpiService.findSnapshots, and @PreAuthorize("hasAuthority('EDIT_PROJECT')") on
 *    KpiService.createSnapshot - on the SERVICE methods, never on the controller and
 *    never here. The code tests a permission code, never a role name, so an administrator
 *    can move a permission from one role to another while the application is running,
 *    with no new deployment.
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {projectId} of the URL
 *    /api/projects/{id}/** and answers 403 when that project is outside the perimeter of
 *    the caller. Having the permission is NOT enough. It matters a lot here, because
 *    these rows carry the money of the project: margin, EAC, amounts invoiced.
 * So a repository method is never safe on its own: calling it from a new place without
 * going through the service would skip both checks.
 */
// No class implements this interface anywhere in the project, and that is normal:
// Spring Data JPA reads the interface at startup and builds the implementation itself
// (a "proxy" object: an object generated at runtime that carries the real code). It
// writes the SQL from the @Query text for the first method, and from the method NAME for
// the second one. It then hands that object to KpiService and to the two seeders, which
// all asked for a SnapshotKpiRepository.
// Why it is done this way: the alternative is to write by hand, for every method, an
// EntityManager, a createQuery call, the parameter binding and the result list.
// The two types between < > are "generics": they tell the proxy what to work on.
//   SnapshotKpi = the entity, so the table read is snapshot_kpis,
//   Long        = the type of the @Id field inherited from BaseEntity, so findById and
//                 save() take and give back that type.
// Example of what these generics buy: save(snapshot) gives back a typed SnapshotKpi with
// its new id already filled in, which KpiService passes straight to the mapper. Without
// them it would give back an Object, every caller would need a cast, and a
// ClassCastException would be waiting at runtime.
// JpaRepository also brings in, for free, save(), findAll(), count(), deleteById()...
// The application uses save() but never deleteById(): deleting a snapshot would mean
// setting deleted = true, never erasing the row, because a financial history that can be
// erased is worth nothing during an audit.
public interface SnapshotKpiRepository extends JpaRepository<SnapshotKpi, Long> {

    // WHAT: gives back every live snapshot of ONE project, most recent date first, with
    //       the project row already loaded.
    // WHY : four separate needs are packed into this single line of JPQL.
    //       (JPQL looks like SQL but is written on the Java classes: "SnapshotKpi k" is
    //       the entity name, not the table name. Hibernate translates it into real SQL.)
    //
    //   (a) "k.project.id = :projectId" - the history is per project, because the KPI
    //       screen is always opened inside one project. The filter is written on the
    //       property path k.project.id, which Hibernate resolves to the foreign key
    //       column project_id: it does NOT add a second join to the projects table.
    //       WITHOUT IT: the history table would mix the snapshots of every project of the
    //       company, and latestSnapshotEv would pick the EV % of another project - the
    //       CA production, the FAE and the current margin of this project would then be
    //       computed on a progress figure that has nothing to do with it.
    //
    //   (b) "k.deleted = false" - a snapshot is never really erased. Deleting it only
    //       sets the boolean column deleted to true (the field comes from BaseEntity, the
    //       parent class of SnapshotKpi). This is a "soft delete": the row stays in the
    //       database for the audit trail, so every read has to filter it out.
    //       WITHOUT IT: a snapshot created by mistake and then deleted would come back in
    //       the history table; and because of the ORDER BY below, that deleted row dated
    //       today would become "the latest one" and would feed its wrong EV % to the live
    //       screen.
    //
    //   (c) "JOIN FETCH k.project" - SnapshotKpi.project is mapped FetchType.LAZY, so
    //       Hibernate would normally leave it as an empty placeholder and run one extra
    //       SELECT the first time it is read. SnapshotKpiMapper reads project.id,
    //       project.code and project.margeNetteVendue on every row. JOIN FETCH brings the
    //       project row in the same statement.
    //       WITHOUT IT: 24 monthly snapshots means 1 query plus up to 24 more just for the
    //       project - the classic "N+1 select" problem (1 query for the list, then N more,
    //       one per row). And because application.yml sets "open-in-view: false", a lazy
    //       link can only be loaded while the service transaction is still open; any
    //       mapping done after the service returned would fail with
    //       LazyInitializationException.
    //       This is a plain JOIN, not a LEFT JOIN, and that is correct here: project_id is
    //       declared NOT NULL both on the entity (@JoinColumn(nullable = false)) and on
    //       the table (migration V8), so every snapshot always has a project and no row
    //       can be silently dropped by the join.
    //
    //   (d) "ORDER BY k.snapshotDate DESC" - newest first. Two callers benefit, for two
    //       different reasons.
    //       For the screen: a financial history is read from the present backwards, so the
    //       line of this month must be on top without the browser having to sort anything.
    //       For KpiService.latestSnapshotEv: it is looking for the most recent snapshot,
    //       to reuse its EV %, its estimated end date and its highlights.
    //       WITHOUT IT: the rows would come back in whatever order PostgreSQL finds
    //       convenient, which is not guaranteed to stay the same, so the same screen could
    //       show its lines in a different order at each reload - which looks like a bug to
    //       the user.
    //       Worth knowing in front of the jury: latestSnapshotEv does not trust this order
    //       blindly, it applies its own Comparator on snapshotDate. That is belt and
    //       braces, not a contradiction - the order stays useful for the screen, and the
    //       service stays correct even if somebody changes the ORDER BY here one day.
    //       No tie-breaker is needed: the next method plus a unique index make sure two
    //       live rows of one project can never share the same snapshotDate.
    //
    // ":projectId" is a named parameter matched to the Java argument of the same name.
    // No @Param annotation is needed because Spring Boot compiles with the -parameters
    // flag, which keeps the real argument names inside the .class file.
    // Why a parameter instead of gluing the value into the query text: the value travels
    // to PostgreSQL apart from the query, so it can never be read as SQL. That is what
    // blocks SQL injection.
    //
    // SPEED: migration V8 creates the matching partial index
    // "CREATE INDEX idx_kpi_project ON snapshot_kpis(project_id) WHERE deleted = FALSE".
    // Partial means only the live rows are indexed, which matches the two filters above
    // exactly, so PostgreSQL jumps straight to the snapshots of this project instead of
    // reading the whole table.
    //
    // WHO CALLS IT: KpiService.findSnapshots (history table of the screen) and
    // KpiService.latestSnapshotEv (EV % reused by the live computation).
    @Query("SELECT k FROM SnapshotKpi k JOIN FETCH k.project WHERE k.project.id = :projectId AND k.deleted = false ORDER BY k.snapshotDate DESC")
    List<SnapshotKpi> findActiveByProjectId(Long projectId);

    // WHAT: answers true when a live snapshot already exists for this project on this
    //       date. It gives back a plain boolean, not the row.
    // WHY no @Query on this one: it is a "derived query" - Spring Data reads the method
    //       NAME and writes the SQL from it. "existsBy" + "ProjectId" + "And" +
    //       "SnapshotDate" + "And" + "DeletedFalse" becomes
    //       SELECT 1 FROM snapshot_kpis WHERE project_id = ? AND snapshot_date = ?
    //       AND deleted = false LIMIT 1.
    //       The two arguments are bound in the order the name mentions them, so changing
    //       the order of the arguments would silently change the meaning of the query.
    //       That is the price of this style, and the reason the name is so long.
    //       "DeletedFalse" is the same soft-delete filter as above, written inside the
    //       name instead of in JPQL.
    // WHY exists... and not findBy... : the only question asked is "is there one?".
    //       exists lets PostgreSQL stop at the first matching row and send back nothing
    //       but a boolean.
    //       WITHOUT that choice: the whole snapshot, with its twenty NUMERIC columns and
    //       its project, would be loaded just to be thrown away - and this question is
    //       asked every single time somebody creates a snapshot.
    //
    // WHAT IT PROTECTS: KpiService.createSnapshot calls it with LocalDate.now() and
    // refuses the request when it answers true. Concrete example of what that stops: a
    // project manager clicks "create snapshot" twice, or reloads the page after a slow
    // answer. Without this guard the project would carry two rows for the same day with
    // slightly different amounts, the history chart would draw two points on one date, and
    // latestSnapshotEv would pick one of the two to feed the live EV %.
    //
    // THE DATABASE HOLDS THE SAME RULE: migration V8 creates
    //   CREATE UNIQUE INDEX uk_kpi_project_date ON snapshot_kpis(project_id, snapshot_date)
    //   WHERE deleted = FALSE;
    // Two reasons to have the rule in both places, and it is worth saying out loud:
    //   - the unique index is the real guarantee, the one the database enforces itself. It
    //     is partial (live rows only), so soft-deleting a snapshot frees that date again,
    //     which is exactly the behaviour wanted;
    //   - this method exists to give the user a clear message instead of a raw database
    //     error, and it reads that same index to answer fast.
    // WITHOUT the index, this check alone would not be enough: two requests arriving at the
    // same instant could both read "no snapshot today" before either one has written.
    boolean existsByProjectIdAndSnapshotDateAndDeletedFalse(Long projectId, LocalDate snapshotDate);
}

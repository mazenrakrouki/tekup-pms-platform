package com.pms.project.repository;

import com.pms.project.entity.LigneDi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * WHAT THIS FILE IS
 * Database access for one table: lignes_di, the lines of the Devis Interne (DI = the
 * internal quote, the sheet where the company compares what it sold on a project with
 * what that work really costs it). A repository is the only place in the application that
 * talks to the database for that table. It carries no business rule and no permission
 * check; both live in the service above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> DevisInterneController (/api/projects/{projectId}/devis-interne)
 *           -> DevisInterneService    (permission MANAGE_DI, transaction, all the maths)
 *           -> LigneDiRepository      (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table lignes_di
 * The LigneDi rows read here never leave the server: DevisInterneService.compute() turns
 * them into LigneDiResponse records packed inside one DevisInterneResponse, and that
 * record is what becomes JSON.
 * Second way in, through the same service: KpiService asks
 * DevisInterneService.computeMargeVenduePct(projectId) for the real margin of the quote,
 * and uses it as the sold-margin baseline of the KPI screen.
 * The inherited save() is used by DevisInterneService for its three write operations:
 * add a line, update a line, and delete a line (a delete is a save with deleted = true).
 *
 * WHY IT EXISTS - what would break if you deleted it
 * DevisInterneService would not compile, so the whole Devis Interne screen would
 * disappear and the KPI screen would fall back to the margin a human typed by hand. The
 * two methods below add exactly what the built-in JpaRepository methods cannot do: they
 * hide the rows flagged deleted = true, they narrow the read to ONE project, and they
 * return the lines in a stable order.
 *
 * HOW THIS FILE RELATES TO THE OTHER FILE OF THE PACKAGE
 * ProjectRepository, next to it, reads the parent table projects. The two are used
 * together inside DevisInterneService: loadProject() goes through
 * ProjectRepository.findActiveById to get the project and its exchange rate - every
 * amount of the quote depends on that rate - and findActiveByProjectId() below gets the
 * lines to which the rate is applied. One repository per table is the rule of this code
 * base, so a change to the projects table never touches this file.
 *
 * WHY THERE IS NO JOIN FETCH HERE, UNLIKE THE OTHER REPOSITORIES OF THE PROJECT
 * RiskRepository and its neighbours write "JOIN FETCH r.project" because their mapper
 * reads the project of every single row. Here the loop in DevisInterneService.compute()
 * reads only the columns of the line itself; the project is loaded once, separately, by
 * loadProject(). Adding a JOIN FETCH would therefore read the same project row again for
 * every line, for nothing.
 *
 * SECURITY - the two protections that are NOT written in this file
 * 1. Permission: @PreAuthorize("hasAuthority('MANAGE_DI')") sits on the SERVICE methods
 *    of DevisInterneService, never on the controller and never here. The code tests a
 *    permission code, never a role name, so an administrator can move MANAGE_DI to
 *    another role while the application is running. The DI holds salaries and margins, so
 *    migration V23 gives MANAGE_DI to the Directeur only - and, because that changes what
 *    a session is allowed to do, the same migration bumps token_version to force those
 *    sessions to pick up a fresh token (ADR-017).
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {projectId} of the URL
 *    /api/projects/{id}/** and answers 403 when that project is outside the perimeter of
 *    the caller. Having the permission is NOT enough.
 * So a method of this file is never safe on its own: calling it from a new place without
 * going through DevisInterneService would skip both checks and expose the cost structure
 * of any project in the company.
 */
// Nothing implements this interface by hand, and that is normal: at start-up Spring Data
// JPA reads the interfaces that extend JpaRepository, writes the SQL from the @Query text
// below, and builds the implementation itself (a "proxy" object), which it hands to
// DevisInterneService. No @Repository annotation is needed, because extending
// JpaRepository is already the signal Spring looks for.
// Why it is done this way: the alternative is to write by hand, for every method, an
// EntityManager, a createQuery call, the parameter binding and the result list.
// The two types between < > are generics - they tell the proxy what to work on:
//   LigneDi = the entity, so the table read is lignes_di,
//   Long    = the type of the @Id field (inherited from BaseEntity), so findById takes a
//             Long.
// Example of what these generics buy: findById(7L) gives back an Optional<LigneDi>
// already typed. Without them the method would return Object, every caller would need a
// cast, and handing a Project where a LigneDi is expected would only blow up at run time,
// in production, instead of being refused by the compiler.
// JpaRepository also brings in, for free, save(), findById(), findAll(), count(),
// deleteById()... DevisInterneService uses save() and findById() but never deleteById():
// deleting a DI line means setting deleted = true, never erasing the row, because the
// quote of a project is an audit trail.
public interface LigneDiRepository extends JpaRepository<LigneDi, Long> {

    // WHAT: gives back every live line of the Devis Interne of ONE project, in a stable
    //       order: section first, then the position chosen inside the section, then the id.
    // WHY : four separate needs are packed into this single line of JPQL.
    //       (JPQL looks like SQL but is written on the Java classes: "LigneDi l" is the
    //       entity name, not the table name lignes_di. Hibernate translates it into real
    //       SQL.)
    //
    //   (a) "l.project.id = :projectId" - a quote is always read inside one project. The
    //       filter is written on the property path l.project.id, which Hibernate resolves
    //       to the foreign key column project_id: it does NOT add a join to the projects
    //       table.
    //       WITHOUT IT: the screen would show the cost lines of every project of the
    //       company - exactly the data the MANAGE_DI permission exists to protect.
    //
    //   (b) "l.deleted = false" - a line is never really erased. DevisInterneService
    //       .deleteLigne only sets the boolean column deleted to true (the field comes
    //       from BaseEntity, the parent class of LigneDi) and saves the row. This is a
    //       "soft delete": the row stays in the database for the audit trail, so every
    //       read has to filter it out.
    //       WITHOUT IT: a line the director removed would come back in the quote AND,
    //       worse, its amount would be added again by DevisInterneService.compute(), so
    //       the total sold, the total cost and the margin of the project would all be
    //       wrong while looking perfectly normal.
    //
    //   (c) the return type is a List and not a Page: a quote has a handful of lines per
    //       section and the screen needs them all at once to show the totals. Paging it
    //       would put part of a total on page 1 and the rest on page 2.
    //
    //   (d) "ORDER BY l.section, l.ordre, l.id" - three sort keys, and each one earns its
    //       place.
    //       * l.section keeps the lines of one section together (HONORAIRES, FRAIS,
    //         AUTRES_FRAIS - see the enum SectionDi, the CHECK constraint
    //         chk_ligne_di_section of migration V23, and F-AFF-13 section 3). Worth
    //         knowing before a jury asks: the section is stored as text
    //         (@Enumerated(EnumType.STRING)), so this sorts the sections ALPHABETICALLY -
    //         AUTRES_FRAIS, then FRAIS, then HONORAIRES - which is not the reading order
    //         of the Excel model. It changes nothing on screen today, because the page
    //         does not print the list as it arrives: devis-interne.component.ts walks its
    //         own fixed list ['HONORAIRES', 'FRAIS', 'AUTRES_FRAIS'] and filters the lines
    //         of each section. What this first key really buys is that the lines of one
    //         section arrive as one block.
    //         If a future screen ever prints the list as-is, the fix is a CASE expression
    //         turning each value into a number - NOT switching the enum to
    //         EnumType.ORDINAL to get a numeric column, because that stores 0, 1, 2 and
    //         silently changes the meaning of every saved row the day somebody inserts a
    //         value in the middle of the enum.
    //       * l.ordre is the position the user chose inside the section, so the quote
    //         keeps the order of the paper document it copies.
    //       * l.id is the tie-break. Ordre defaults to 0 (see LigneDi, and the DEFAULT 0
    //         of migration V23), so several lines can easily share the same value, and a
    //         database is free to return equal rows in any order.
    //         WITHOUT this third key: two lines both at ordre 0 could swap places between
    //         two page loads, so the quote would reshuffle itself under the eyes of the
    //         director while he is typing in it.
    //
    // ":projectId" is a named parameter matched to the Java argument of the same name. No
    // @Param annotation is needed because Spring Boot compiles with the -parameters flag,
    // which keeps the real argument names inside the .class file.
    // Why a parameter instead of gluing the value into the query text: the value travels
    // to PostgreSQL apart from the query, so it can never be read as SQL. That is what
    // blocks SQL injection.
    //
    // SPEED: migration V23 creates the matching partial index
    // "CREATE INDEX idx_ligne_di_project ON lignes_di(project_id) WHERE deleted = FALSE".
    // Partial means only the live rows are indexed, which matches filters (a) and (b)
    // exactly, so PostgreSQL jumps straight to the lines of this project instead of
    // reading the whole table.
    //
    // WHO CALLS IT: DevisInterneService, on all five of its methods - the plain read, the
    // three writes (which re-read the lines afterwards, so the totals sent back are the
    // fresh ones), and computeMargeVenduePct used by KpiService.
    @Query("SELECT l FROM LigneDi l WHERE l.project.id = :projectId AND l.deleted = false ORDER BY l.section, l.ordre, l.id")
    List<LigneDi> findActiveByProjectId(Long projectId);

    // WHAT: answers true when the project has at least one live DI line, false otherwise,
    //       without loading a single line.
    // WHY not simply call findActiveByProjectId(id).isEmpty(): that would carry every line
    //       and all its numeric columns from PostgreSQL into the JVM only to look at the
    //       size of the list. Here the counting happens inside the database and one small
    //       value travels back.
    //       Concrete example: on a project with 40 DI lines, this answers with a single
    //       value instead of 40 rows of fifteen numeric columns.
    // "COUNT(l) > 0" is written inside the SELECT: the comparison is evaluated by the
    //       database, so what comes back is already a yes/no and the Java return type can
    //       be a plain boolean that is never null. (Writing a comparison in the SELECT
    //       clause is a Hibernate extension to JPQL; the strictly portable form is
    //       "CASE WHEN COUNT(l) > 0 THEN true ELSE false END".)
    // WHY a boolean rather than the count as a number: the caller only wants a yes or a
    //       no. Returning a number invites code like "if (count == 1)", which breaks the
    //       day a project has two lines.
    // The two filters are the same as above and are there for the same reasons:
    // ":projectId" keeps the question inside one project, "l.deleted = false" ignores the
    // soft-deleted lines.
    // WITHOUT the deleted filter: a project whose lines have all been deleted would still
    // be reported as "has a quote", so a screen could open an empty DI, or the KPI screen
    // could believe a computed baseline exists when there is none.
    // The partial index idx_ligne_di_project (migration V23) serves this query too.
    //
    // WHO CALLS IT TODAY: no caller in the application - say that plainly rather than
    // guessing. It is the cheap way to ask "does this project have a quote at all?", for
    // instance to show or hide the DI tab. The one place that asks that question today,
    // DevisInterneService.computeMargeVenduePct, needs the lines themselves anyway, so it
    // reads them and tests whether the list is empty.
    @Query("SELECT COUNT(l) > 0 FROM LigneDi l WHERE l.project.id = :projectId AND l.deleted = false")
    boolean existsActiveByProjectId(Long projectId);
}

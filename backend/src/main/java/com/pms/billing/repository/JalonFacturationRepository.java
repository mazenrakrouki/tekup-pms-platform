package com.pms.billing.repository;

/*
 * ============================================================================
 *  JalonFacturationRepository - the database door for billing milestones.
 * ============================================================================
 *
 *  WHAT THIS FILE IS
 *  A "jalon de facturation" (billing milestone) is one step of the payment plan
 *  agreed with the client: "30% of the budget when the specification is signed",
 *  "50% at delivery", and so on. Each milestone carries a percentage, the amount
 *  that percentage represents, and a status: PREVU (planned), FACTURE (invoiced),
 *  PAYE (paid). This interface is the only place in the application that reads the
 *  "jalons_facturation" table.
 *
 *  WHERE IT SITS IN THE FLOW (who calls it, what it calls next)
 *    Angular billing page
 *      -> BillingController, mapped on /api/projects/{projectId}/jalons
 *      -> ProjectScopeInterceptor. ADR-021: on every URL matching
 *         /api/projects/{id}/ followed by anything, the interceptor checks BOTH the
 *         permission AND the project scope - being allowed to manage billing
 *         somewhere does not make you allowed to manage billing on THIS project.
 *      -> JalonService, where the permission test actually sits on the method
 *         (hasAuthority('VIEW_BILLING') to read, hasAuthority('MANAGE_BILLING') to
 *         write). Authorization here is dynamic and permission-based: the code never
 *         tests a role name, it tests a permission the administrator can move from
 *         one role to another at runtime.
 *      -> JalonFacturationRepository (this file)
 *      -> PostgreSQL table "jalons_facturation", created by the Flyway migration
 *         V9__schema_billing.sql.
 *  The entities that come back go to JalonMapper, which builds the JalonResponse
 *  records sent to Angular.
 *
 *  THREE OTHER CALLERS WORTH KNOWING
 *   - AvenantService: when an amendment changes the budget it calls
 *     JalonService.recomputePrevuMontants(), which uses the last method of this file.
 *   - KpiService: calls findActiveByProjectId() to add up what has already been
 *     invoiced on a project.
 *   - The demo/enterprise data seeders, to create sample milestones at start-up.
 *
 *  WHY IT EXISTS (what breaks if you delete it)
 *  Spring Data JPA builds the implementation of this interface at start-up, so no
 *  handwritten SQL is needed for milestones. Remove this file and JalonService,
 *  PaiementService and KpiService all stop compiling: the payment plan of a project
 *  disappears, and with it the "invoiced" figure of the KPI screen.
 *
 *  TWO IDEAS RUN THROUGH THE WHOLE FILE
 *   1. SOFT DELETE. A milestone is never physically removed; JalonService.delete()
 *      only sets deleted = true (column inherited from BaseEntity), because billing
 *      history must stay auditable. So every query written here must filter
 *      "deleted = false" itself - the inherited findAll()/findById() do not know
 *      about that flag.
 *   2. AMOUNTS ARE COMPUTED, NEVER TYPED BY HAND. The montant of a milestone always
 *      equals effectiveBudget x pourcentage / 100. Careful with the wording in front
 *      of a jury: here the value IS stored in the montant column, and H-4 is exactly
 *      the decision to recompute that stored value whenever the budget moves (an
 *      invoiced amount must stay what was printed on the invoice, so it cannot simply
 *      be recomputed at read time). This is the opposite choice from the DI (Devis
 *      Interne, the internal quote), where computed amounts are never stored at all
 *      and are derived on every read. Both choices serve the same goal: a computed
 *      amount must never drift away from the figures it comes from.
 */

import com.pms.billing.entity.JalonFacturation;
import com.pms.billing.entity.JalonStatut;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Read and write access to the JalonFacturation entity. It gives back either
 * milestone entities (one, or a List) or a single computed BigDecimal total.
 *
 * WHY AN INTERFACE WITH NO BODY
 * We extend JpaRepository&lt;JalonFacturation, Long&gt;. The two types inside the
 * angle brackets are "generics": the entity handled, and the type of its primary key
 * (Long, from BaseEntity.id). Spring Data JPA generates the implementing class at
 * start-up, so save(), saveAll(), findById() and friends already exist without a line
 * of code from us. The alternative - a DAO class holding an EntityManager and SQL
 * strings - would repeat the same eighty lines for every entity of the project, and a
 * wrong column name would only be discovered at runtime instead of at boot.
 */
public interface JalonFacturationRepository extends JpaRepository<JalonFacturation, Long> {

    // The annotation below holds JPQL, a query language written in terms of Java
    // classes and fields ("JalonFacturation j", "j.datePrevue") which Hibernate turns
    // into real SQL over the table jalons_facturation. We write it by hand instead of
    // letting Spring derive it from the method name, because a derived name cannot
    // express JOIN FETCH.
    //
    // JOIN FETCH j.project: bring the parent Project back in the same single query.
    // Why: JalonFacturation.project is mapped with fetch = FetchType.LAZY, so Hibernate
    // would normally return an empty stand-in object (a "proxy") and hit the database
    // again the first time a real field of the project is read. JalonMapper reads
    // project.code to fill JalonResponse.projectCode, so that moment always comes.
    // Example without it: a project with 8 milestones runs 1 query plus 8 extra ones to
    // read the very same project code eight times - the well-known "N+1 queries"
    // problem. Nothing looks wrong on screen, the page simply gets slower and slower as
    // payment plans grow. And if the mapping ever happened outside the transactional
    // service method, the proxy would find no open session and throw
    // LazyInitializationException instead.
    //
    // WHERE j.project.id = :projectId: only the milestones of the requested project.
    // Reading j.project.id costs nothing extra, because project_id is already a column
    // of the jalons_facturation row itself.
    // Example without it: the billing screen of project A would display the payment plan
    // of every project of the company.
    //
    // AND j.deleted = false: skip soft-deleted milestones.
    // Example without it: a milestone deleted by mistake and re-created would appear
    // twice, and KpiService would count its amount twice in the invoiced total.
    //
    // ORDER BY j.datePrevue NULLS LAST, j.id: sort by planned date, and push the
    // milestones that have no planned date yet to the bottom of the list.
    // Why NULLS LAST is written explicitly: date_prevue is a nullable column, and
    // databases disagree on where NULLs belong when sorting. PostgreSQL (production)
    // puts them last in ascending order, H2 (used by the tests, in PostgreSQL mode)
    // puts them first. Spelling the rule out gives the same order everywhere.
    // Example without it: a milestone with no date would jump to the top of the payment
    // plan in the tests and sit at the bottom in production, so a test could pass on the
    // developer machine and the screen still look wrong to the client.
    // Why ", j.id" at the end: two milestones can share the same planned date, and then
    // the database is free to return them in any order. The id is unique, so it acts as
    // a tie-breaker. Example without it: two milestones planned on 31/12 could swap
    // places between two refreshes of the same page.
    /**
     * The full payment plan of one project - every milestone still alive - sorted by
     * planned date, with the parent Project already loaded.
     *
     * Called by JalonService.findByProject() for the billing screen, and by KpiService
     * to add up the amounts already invoiced on the project.
     */
    @Query("SELECT j FROM JalonFacturation j JOIN FETCH j.project WHERE j.project.id = :projectId AND j.deleted = false ORDER BY j.datePrevue NULLS LAST, j.id")
    List<JalonFacturation> findActiveByProjectId(Long projectId);

    // Same recipe for a single row: JOIN FETCH so the Project travels with the milestone,
    // and j.deleted = false so a deleted milestone can never be invoiced or paid again.
    //
    // Optional<JalonFacturation> rather than a plain entity: Optional is a box that is
    // either full or empty, and the compiler obliges the caller to open it.
    // Why: not finding the milestone is a normal outcome (bad id in the URL), not a bug.
    // JalonService.loadJalon() writes .orElseThrow(() -> new NotFoundException(...)) so
    // the user receives a clean 404 saying "Jalon introuvable".
    // Example without Optional: the method returns null, some caller forgets the test,
    // and a mistyped id produces a NullPointerException 500 page.
    //
    // On purpose, this query does NOT filter on the project. The project check lives one
    // level up, in JalonService.checkBelongsToProject() and in PaiementService, which
    // compare jalon.getProject().getId() with the projectId read from the URL. With
    // ADR-021 that makes two independent barriers.
    // Example without that second check: someone allowed on project A could call
    // /api/projects/A/jalons/{id of a milestone of project B} and mark a milestone of
    // project B as invoiced.
    /**
     * One milestone by its id, only if it has not been soft-deleted.
     *
     * Called by JalonService.loadJalon(), which is itself the entry point of update(),
     * facturer(), delete() and of both PaiementService methods.
     */
    @Query("SELECT j FROM JalonFacturation j JOIN FETCH j.project WHERE j.id = :id AND j.deleted = false")
    Optional<JalonFacturation> findActiveById(Long id);

    // SUM(j.pourcentage): let the database add the percentages up instead of loading
    // every milestone into Java and looping over them.
    // Why: only one number crosses the network. Example without it, checking the rule
    // before saving one milestone would drag the whole payment plan into memory just to
    // throw it away right after.
    //
    // COALESCE(..., 0): COALESCE returns its first argument that is not null, so it
    // turns a null sum into zero. This is the important part of the line.
    // Why: in SQL, SUM over zero rows returns NULL, not 0. The caller,
    // JalonService.validatePourcentageSum(), immediately does current.subtract(...) on
    // the result.
    // Example without COALESCE: creating the very first milestone of a brand-new project
    // finds no row, SUM gives NULL, the method returns null, and the next line throws a
    // NullPointerException - so nobody could ever create a first milestone anywhere.
    //
    // No JOIN FETCH here, and that is deliberate: this query returns a number, not an
    // entity, so there is no lazy field anybody could read afterwards.
    //
    // AND j.deleted = false: soft-deleted milestones must not use up percentage budget.
    // Example without it: a plan of 30% + 70% where the 70% was deleted would already
    // count as 100%, and the user would be refused a new milestone on a project whose
    // payment plan is in fact only 30% full.
    //
    // BigDecimal, not double: the pourcentage column is NUMERIC(5,2) (money columns are
    // NUMERIC(15,2)) and these figures must be exact. Example with double: 33.33 + 33.33 + 33.34 can come out as 100.00000001
    // and the "sum must stay under 100%" rule would reject a perfectly valid plan.
    /**
     * Total of the percentages already used by the live milestones of one project.
     * Returns 0 (never null) when the project has no milestone yet.
     *
     * Called by JalonService.validatePourcentageSum() before create() and update(), to
     * enforce the business rule "the payment plan of a project cannot exceed 100% of the
     * budget" - the same rule the database also guards per row with the CHECK constraint
     * chk_jf_pourcentage in V9__schema_billing.sql.
     */
    @Query("SELECT COALESCE(SUM(j.pourcentage), 0) FROM JalonFacturation j WHERE j.project.id = :projectId AND j.deleted = false")
    BigDecimal sumPourcentageByProjectId(Long projectId);

    // This one carries no annotation: it is a "derived query". Spring Data reads the
    // method name and builds the SQL from it, piece by piece:
    //   findBy        -> a SELECT
    //   ProjectId     -> walks from the milestone to its project, then to project.id
    //   And Statut    -> ... AND statut = ? (second argument)
    //   And DeletedFalse -> ... AND deleted = false (no argument needed, the value is
    //                       written in the name itself)
    // Why this style here while the queries above are handwritten: this one needs no
    // JOIN FETCH, so the name says everything and there is no query string to keep in
    // sync. No JOIN FETCH is needed because recomputePrevuMontants() only reads
    // j.getPourcentage() and writes j.setMontant(); it never touches the project through
    // the milestone - it already receives the Project as a method argument.
    // Example if the name were wrong (say "findByProjectIdAndStatus"): the application
    // refuses to start with "No property 'status' found" - the mistake is caught at boot,
    // not in front of the client.
    //
    // JalonStatut statut is an enum, stored as text thanks to @Enumerated(EnumType.STRING)
    // on the entity, so the generated SQL compares against 'PREVU'.
    // Why the callers always pass PREVU: FACTURE and PAYE milestones are frozen. Once an
    // invoice has been sent to the client, its amount belongs to accounting and must not
    // move any more.
    // Example without that restriction: the client receives an invoice for 30 000 TND, an
    // amendment later raises the budget, and the stored amount silently becomes 33 000 -
    // the application would then contradict a paper document the client already holds.
    /**
     * H-4: the PREVU (planned) milestones whose amount has to be recomputed when the
     * effective budget of the project changes.
     *
     * Called by JalonService.recomputePrevuMontants(), which itself is called by
     * AvenantService.create() and AvenantService.delete() - every time an amendment moves
     * the revised budget. The amount of a milestone is always
     * effectiveBudget x pourcentage / 100, so it must follow the budget.
     * Example of what H-4 fixed: a plan of 30% on a budget of 100 000 shows 30 000; an
     * amendment brings the budget to 120 000; without this recompute the milestone would
     * still display 30 000 while the correct value is 36 000, and the sum of the payment
     * plan would no longer match the contract.
     */
    List<JalonFacturation> findByProjectIdAndStatutAndDeletedFalse(Long projectId, JalonStatut statut);
}

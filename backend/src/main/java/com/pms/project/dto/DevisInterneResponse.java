package com.pms.project.dto;

import java.math.BigDecimal;
import java.util.List;

/*
 * FILE: DevisInterneResponse.java
 *
 * WHAT THIS FILE IS
 * The single answer object sent back to the browser for the whole "Devis Interne" (DI) of one
 * project. "Devis Interne" means "internal quote": the sheet where the company writes what it
 * sold to the client, what the work really costs it, and the margin left between the two.
 * This is a DTO (Data Transfer Object): a small object whose only job is to carry data from the
 * server to the client. It is not a database table and it contains no logic.
 *
 * WHERE IT SITS IN THE FLOW
 *   DevisInterneController  (/api/projects/{projectId}/devis-interne, GET / POST / PUT / DELETE)
 *     -> DevisInterneService.getDevisInterne / addLigne / updateLigne / deleteLigne
 *     -> DevisInterneService.compute(project, lignes) builds THIS record
 *     -> Spring (Jackson) turns it into JSON for the Angular DI screen.
 * It calls nothing itself; it is the last stop of the read path. It holds the list of
 * LigneDiResponse (one entry per line of the quote) plus the totals of those lines.
 * Note that the three write endpoints also return this object: after adding, changing or
 * deleting a line the client immediately gets the whole recomputed quote, so the totals on
 * screen can never drift away from the totals on the server.
 *
 * WHY IT EXISTS
 * Delete it and the service would have to send the LigneDi database entities straight out.
 * Two things would break. First, LigneDi has a lazy link back to Project, so writing it as JSON
 * would either fail or drag the whole project graph out of the database. Second, and more
 * important, the totals and the margins are NOT database columns: they are recomputed on every
 * read (F-AFF-13 rule: an amount is never stored twice, once per currency). No entity can carry
 * them, so this record is the only place where the computed view of the DI exists.
 *
 * SECURITY NOTE
 * Every number here is commercially sensitive. Every service method that RETURNS this object
 * carries hasAuthority('MANAGE_DI') (the check sits on the service, never on the controller),
 * and the URL /api/projects/{projectId}/** is checked a second time by ProjectScopeInterceptor,
 * which verifies that this user may touch THIS project (ADR-021: permission AND scope, never
 * permission alone). Unlike ProjectResponse there is no redacted copy of this record: a user
 * without MANAGE_DI never receives the object at all, so there is nothing to strip.
 *
 * The exact wording "every method that returns it" matters, and a jury may push on it. There is
 * one method that BUILDS this object without MANAGE_DI: DevisInterneService.computeMargeVenduePct(),
 * called only by KpiService. It builds the record in memory, reads the single margePct value out
 * of it, and lets the rest be discarded. So what leaves that path is one aggregated percentage,
 * an indicator, never a line of the quote and never an internal cost price. No controller maps
 * to it either, so it cannot be reached from outside.
 */

/**
 * Full Devis Interne of one project: the lines, then the totals over those lines.
 *
 * Why a record: a record is an immutable data holder. Java generates the constructor, the
 * accessors and equals() and the fields can never be changed afterwards. That is exactly what a
 * response needs. A normal class with setters would let a later layer quietly rewrite a total
 * after the calculation engine produced it.
 *
 * Why 12 named components instead of a Map of totals: the Angular screen and the generated
 * OpenAPI/Swagger documentation both need a fixed, named, typed contract. With a Map, renaming
 * one total would break the screen silently at runtime instead of failing at compile time.
 */
public record DevisInterneResponse(
        // Database id of the project this quote belongs to.
        Long projectId,
        // Human code of the project, for example "PRJ-2024-07".
        // Why it travels with the quote: the DI screen can print its own title and the file name
        // of an export without calling GET /api/projects/{id} a second time.
        String projectCode,
        // Currency the project sells in (TND, EUR, FCFA...), copied from the project row.
        // Why: every "Devise" amount below is expressed in this currency, so the screen must be
        // able to label them. Without it a value of 1 000 000 could be read as euros.
        String currency,
        // Exchange rate used to turn one unit of that currency into Tunisian dinars.
        // Why it is returned and not kept private: the user must be able to redo the arithmetic
        // by hand and find the same figure. Example: 1 000 000 FCFA at 0.005850 gives 5 850 TND;
        // without this field nobody on the screen can explain where 5 850 came from.
        // The engine falls back to 1 when the project has no rate, so this is never null.
        BigDecimal exchangeRateToTnd,
        // The quote lines themselves, already computed and in the stable order set by the
        // repository query (section first, then the position chosen by the user, then the id).
        // The generic part <LigneDiResponse> tells the compiler, Jackson and the generated
        // TypeScript model what each element is. Why it matters: with a raw List the compiler
        // stops checking, and a wrong object slipped into the list would only explode later in
        // the browser, with no useful error.
        List<LigneDiResponse> lignes,
        // -- totals over the lines above --
        // Sum of every line amount, still in the project currency (charge sold x unit price).
        BigDecimal totalVenduDevise,
        // The same total converted into dinars, rounded to 2 decimals.
        // Why both are sent: the company signs in the contract currency but steers the business
        // in dinars. Keeping only one would force the screen to convert, and a screen rounding
        // differently from the server is how two "official" margins start to disagree.
        BigDecimal totalVenduTnd,
        // Total workload sold, in JH ("jour-homme", one person working one day).
        // This is a quantity, not money: it is what the client pays for.
        BigDecimal totalChargeVendueJh,
        // Total workload the company actually plans to spend internally, also in JH.
        // Why it sits next to the sold workload: the gap between the two is the first warning
        // sign of a badly priced project, before any money is even converted.
        BigDecimal totalQuantiteInterneJh,
        // Total real cost of the project in dinars: internal cost of the lines, plus the extra
        // fees, plus the percentage-based lines (taxes, risk provision).
        BigDecimal totalCoutFinal,
        // Net margin in dinars = totalVenduTnd - totalCoutFinal.
        BigDecimal margeNette,
        // Net margin as a fraction of the sold amount = margeNette / totalVenduTnd, 4 decimals,
        // so 0.4412 means 44.12 %.
        // It is null, not zero, when nothing has been sold yet (totalVenduTnd is 0 or less),
        // because dividing by zero has no meaning. Why null matters: the screen can then print
        // "-" instead of a confident "0 %", which a reader would take for a project with no margin.
        // This same figure is the computed "marge nette vendue" baseline: when a project has DI
        // lines, DevisInterneService.computeMargeVenduePct() hands it to the KPI engine instead
        // of the value typed by hand on the project sheet.
        BigDecimal margePct
) {}

package com.pms.user.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

// ============================================================================
// FILE: ResourceResponse
//
// WHAT THIS FILE IS
//   The read-only shape sent to the browser for ONE resource: who the person
//   is, what they cost per day, their TCC rate (taux de charges - the extra
//   cost added on top of the daily rate) and their staffing dates. It is a DTO
//   (Data Transfer Object): a small flat object built only to travel over HTTP.
//
// WHERE IT SITS IN THE FLOW
//   Resource entity (table "resources")
//     -> ResourceMapper, in package com.pms.user.mapper, which MapStruct turns
//        into real code at compile time
//     -> ResourceService (findAll, findById, create, update)
//     -> ResourceController: GET /api/resources, GET /api/resources/{id},
//        and the answer of POST and PUT
//     -> Angular resources.component, which reads it through the "Resource"
//        interface of core/models/user.model.ts.
//
// WHY IT EXISTS
//   Returning the Resource entity itself would drag two problems along. First,
//   Resource.user is a whole User object, so Jackson (the library that turns
//   Java objects into JSON) would publish that person's account - including
//   fields no screen needs. Second, Resource.user is mapped with
//   FetchType.LAZY and application.yml sets open-in-view: false, so the
//   database session is already closed when the response is written; touching
//   the user at that moment throws LazyInitializationException. The mapper
//   flattens the user into two plain values while the transaction is still
//   open, which removes both problems at once.
//
// WHO IS ALLOWED TO SEE THIS
//   ResourceService.findAll() is guarded by
//   @PreAuthorize("hasAuthority('VIEW_RESOURCES')") on the service method.
//   A holder of MANAGE_RESOURCES (admin, direction) gets the whole catalogue;
//   a project manager with VIEW_RESOURCES alone only gets the resources of the
//   projects he manages - that is the data scope of ADR-021. So the same record
//   is used for both, but the LIST behind it is not the same.
// ============================================================================

/**
 * One resource, as the client sees it.
 *
 * <p>Every field is filled by ResourceMapper.toResponse(). Three of them are
 * not plain copies of a column, and each has its own note below.
 *
 * <p>Why a record and not a class with getters and setters: a response is
 * built once, written to JSON, then thrown away. A record is immutable, so no
 * code between the mapper and the JSON writer can change an amount by mistake.
 */
public record ResourceResponse(
        // Primary key of the "resources" row. The Angular screen sends it back
        // in the URL of PUT /api/resources/{id} and of the yearly TCC endpoints
        // (/api/resources/{id}/tcc).
        Long id,

        // Id of the account behind this resource. Filled by the mapper rule
        // @Mapping(target = "userId", source = "user.id").
        // Why it is sent even though userFullName is already there: the screen
        // needs the id to know which users still have no resource line, so the
        // "create a resource" form can offer only those.
        Long userId,

        // "Firstname Lastname", ready to print. The mapper builds it with the
        // @Named("fullName") helper, which calls User.getFullName() and returns
        // null when there is no user.
        // Why a flat string instead of the User object: the list screen only
        // prints a label, and sending the account would expose fields the cost
        // screen has no reason to know.
        String userFullName,

        // The two rates exactly as stored: daily_rate is NUMERIC(10,2),
        // tcc_rate is NUMERIC(5,4) where 0.4200 means 42%.
        // BigDecimal and not double: a double cannot hold 0.1 exactly, so money
        // added in double slowly drifts; BigDecimal keeps the exact decimal
        // value. Jackson writes it as a JSON number, and the Angular interface
        // reads it as a number.
        BigDecimal dailyRate,
        BigDecimal tccRate,

        // Indicative yearly cost. This is a COMPUTED value: there is no
        // annual_cost column anywhere. The mapper fills it with
        // @Mapping(target = "annualCost", expression = "java(resource.getAnnualCost())"),
        // and that method does dailyRate x (1 + tccRate) x 218 working days,
        // rounded to two decimals (HALF_UP).
        //
        // Why computed instead of stored: a stored amount goes stale the moment
        // somebody edits a rate, and the screen would then show a yearly cost
        // that no longer matches the rates printed right next to it. The same
        // rule is applied everywhere in the application for money.
        //
        // Careful with what this number means: it uses the resource's BASE
        // rates only. It ignores the per-year rates of the tcc_annuels table
        // (spec F-AFF-13 section 6.3 rule 4, TCC 2024 is not TCC 2025), so it
        // is a rough figure for the list screen. The real margin figures are
        // computed by KpiService, which applies the rate of the year the work
        // was charged to.
        BigDecimal annualCost,

        // Staffing window. staffingEnd is null when the staffing is still
        // open-ended, so the Angular interface declares it optional and the
        // screen must print something like "-" rather than assume a date.
        LocalDate staffingStart,
        LocalDate staffingEnd
) {}

package com.pms.governance.dto;

/*
 * WHAT THIS FILE IS
 * The shape of the JSON the API sends back for one stakeholder
 * ("partie prenante"). It is the read side, the mirror of
 * PartiePrenanteRequest.
 *
 * WHERE IT SITS IN THE FLOW
 * PartiePrenanteRepository loads PartiePrenante entities
 *   -> PartiePrenanteMapper.toResponse() / toResponseList() turn them into this
 *      record. MapStruct (ADR-018) generates that mapper at compile time into a
 *      class named PartiePrenanteMapperImpl.
 *   -> PartiePrenanteService returns it
 *   -> PartiePrenanteController wraps it in ResponseEntity
 *   -> Jackson turns it into JSON
 *   -> Angular reads it as the "PartiePrenante" interface in
 *      core/models/partie-prenante.model.ts, and governance.component.ts shows
 *      influence and interet as two coloured badges.
 *
 * WHY IT EXISTS
 * Without it the controller would return the PartiePrenante entity, and:
 * 1) the entity holds "@ManyToOne(fetch = FetchType.LAZY) private Project
 *    project". Lazy means the project is fetched only when something asks for
 *    it; Jackson asks for everything, so the call would either fail with a
 *    LazyInitializationException once the transaction is closed, or drag the
 *    whole project graph into the response.
 * 2) the entity also carries deleted, createdAt and createdBy from BaseEntity,
 *    internal columns that must not be published.
 * This record publishes nine flat values and nothing else.
 *
 * ONE MORE REASON HERE: this is the only place in the governance module that
 * returns personal data (a name, an email, a phone number). Having one explicit
 * list of what is sent makes it easy to answer the question "what personal data
 * does this endpoint expose?" - the answer is this file, not "whatever the
 * entity happens to contain today".
 */

import com.pms.governance.entity.NiveauRisque;

/**
 * WHAT IT IS
 * An immutable record used only for reading. Its component names are the JSON
 * keys the browser receives, so this file is the real contract with the Angular
 * "PartiePrenante" interface.
 *
 * WHY IT IS FLAT
 * The entity has a nested Project; this record has projectId and projectCode
 * side by side. PartiePrenanteMapper fills them with two rules: target
 * "projectId" comes from source "project.id", and target "projectCode" comes
 * from source "project.code" (the two MapStruct @Mapping lines), so the slice
 * of Project that is exposed is decided once, here, and not by each screen.
 */
public record PartiePrenanteResponse(
        // Primary key. The front end uses it to build the next URLs, for example
        // DELETE /api/projects/7/parties-prenantes/42, and to track table rows.
        Long id,

        // Filled by MapStruct from project.id.
        // Without that @Mapping line MapStruct would look for a getProjectId() on
        // the entity, not find one, leave the field null and only print a build
        // warning, so the mistake would surface in the browser instead of at
        // compile time.
        Long projectId,

        // Filled by MapStruct from project.code: the short readable project code.
        // It lets a view that mixes stakeholders of several projects show where
        // each line belongs without a second call to /api/projects/{id}.
        String projectCode,

        String nom,

        // These three may be null, exactly like their nullable columns, which is
        // why the Angular interface declares them with a question mark
        // ("fonction?: string"). The front end must handle the empty case; it
        // shows a dash.
        String fonction,
        String email,
        String telephone,

        // Power over the project and interest in the project, on the shared
        // three-level scale FAIBLE / MOYEN / ELEVE.
        // Sent as the enum names, so Jackson writes "influence": "ELEVE". Those
        // exact strings match the TypeScript union type NiveauRisque and the
        // Transloco keys riskLevel.FAIBLE / riskLevel.MOYEN / riskLevel.ELEVE,
        // which is how the badge text gets translated in the browser. With a
        // plain String instead of an enum, one typo on the server would produce a
        // key Transloco cannot find and the cell would display the raw key.
        // The two are returned separately, not merged into a single score,
        // because it is the pair that places the person on the power/interest
        // grid and tells the manager how to deal with them.
        NiveauRisque influence,
        NiveauRisque interet
) {}

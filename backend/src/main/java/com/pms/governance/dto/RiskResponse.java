package com.pms.governance.dto;

/*
 * WHAT THIS FILE IS
 * The shape of the JSON the API sends back for one risk. It is the read side
 * of the risk register, the mirror of RiskRequest.
 *
 * WHERE IT SITS IN THE FLOW
 * RiskRepository loads Risk entities
 *   -> RiskMapper.toResponse() / toResponseList() turn each entity into this
 *      record. MapStruct (ADR-018) writes that mapper code at compile time, in a
 *      generated class named RiskMapperImpl.
 *   -> RiskService returns it
 *   -> RiskController wraps it in ResponseEntity
 *   -> Jackson turns it into JSON
 *   -> the Angular side reads it as the "Risk" interface in
 *      core/models/governance.model.ts, and governance.component.ts draws one
 *      coloured badge for probabilite and one for impact.
 *
 * WHY IT EXISTS
 * Without it the controller would return the Risk entity itself, and two things
 * would break.
 * 1) Risk has "@ManyToOne(fetch = FetchType.LAZY) private Project project".
 *    Lazy means the project is not loaded until someone asks for it. Jackson
 *    asks for everything, so it would either explode with a
 *    LazyInitializationException once the transaction is closed, or load the
 *    whole project, then its client, then its team... and send a huge JSON.
 * 2) The entity also carries deleted, createdAt, createdBy from BaseEntity.
 *    Those are internal bookkeeping and have no reason to be published.
 * This record publishes exactly eight flat values, and nothing else can leak.
 */

import com.pms.governance.entity.NiveauRisque;
import com.pms.governance.entity.StatutRisque;

/**
 * WHAT IT IS
 * An immutable record used only for reading. The field names here are the JSON
 * keys the front end receives, so this file is in practice the contract between
 * the backend and the Angular "Risk" interface.
 *
 * WHY IT IS FLAT
 * The entity has a nested Project object; this record has projectId and
 * projectCode side by side instead. RiskMapper fills them with two rules:
 * target "projectId" comes from source "project.id", and target "projectCode"
 * comes from source "project.code" (the two MapStruct @Mapping annotations).
 * A flat shape is easier to bind in an Angular table and it decides, in one
 * place, exactly how much of Project the client is allowed to see.
 */
public record RiskResponse(
        // Database primary key of the risk. The front end needs it to build the
        // URLs of the next calls, for example
        // DELETE /api/projects/7/risks/42, and to use it as the *ngFor track key.
        Long id,

        // Filled by MapStruct from project.id.
        // Why repeat it when the client already knows the project from the URL:
        // the same record can be reused in a list that mixes several projects
        // (a dashboard, a CSV export) without a second call to find out where
        // each row belongs.
        // Without the @Mapping line in RiskMapper, MapStruct would find no
        // getProjectId() on the entity, leave this at null and only print a
        // build warning - the bug would show up in the browser, not at compile
        // time.
        Long projectId,

        // Filled by MapStruct from project.code: the short readable code of the
        // project, the one a human recognises, instead of a technical number.
        // Why: a screen that shows this row outside the project page can print
        // the code directly, with no extra call to /api/projects/{id}.
        String projectCode,

        String description,

        // The two levels are sent as the enum names, so Jackson writes
        // "probabilite": "ELEVE". Those exact strings match the TypeScript union
        // type NiveauRisque = 'FAIBLE' | 'MOYEN' | 'ELEVE' and the Transloco keys
        // riskLevel.FAIBLE / riskLevel.MOYEN / riskLevel.ELEVE, which is how the
        // label gets translated to French or English in the browser.
        // Without the enum (a plain String), a typo on the backend side would
        // produce a key that Transloco cannot find and the cell would show the
        // raw key instead of a word.
        NiveauRisque probabilite,
        NiveauRisque impact,

        // May be null: a risk can be recorded before anyone has written a
        // mitigation plan. The Angular interface therefore declares it
        // "planMitigation?: string".
        String planMitigation,

        // OUVERT, MITIGE or FERME. Sent as a value, not as a pre-computed colour
        // or label, so the decision about how to display it stays in the front
        // end and the API stays language-neutral.
        StatutRisque statut
) {}

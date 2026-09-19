package com.pms.governance.dto;

/*
 * WHAT THIS FILE IS
 * The shape of the JSON the API sends back for one deliverable ("livrable").
 * It is the read side, the mirror of LivrableRequest.
 *
 * WHERE IT SITS IN THE FLOW
 * LivrableRepository loads Livrable entities
 *   -> LivrableMapper.toResponse() / toResponseList() turn them into this record.
 *      MapStruct (ADR-018) writes that mapper at compile time into a generated
 *      class named LivrableMapperImpl, so there is no reflection and no
 *      hand-written copy code to keep in sync.
 *   -> LivrableService returns it (it is also what the three state-machine
 *      endpoints demarrer / livrer / valider return, so the screen gets the new
 *      status without a second GET)
 *   -> LivrableController wraps it in ResponseEntity
 *   -> Jackson turns it into JSON
 *   -> Angular reads it as the "Livrable" interface in
 *      core/models/governance.model.ts.
 *
 * WHY IT EXISTS
 * Without it the controller would return the Livrable entity, and:
 * 1) Livrable holds "@ManyToOne(fetch = FetchType.LAZY) private Project project".
 *    Lazy means the project is loaded only when something asks for it. Jackson
 *    asks for everything, so the call would either fail with a
 *    LazyInitializationException after the transaction closes, or pull the whole
 *    project graph into the response.
 * 2) The entity also carries deleted, createdAt and createdBy from BaseEntity -
 *    internal columns that have no business being published.
 * This record publishes seven flat values and nothing more.
 */

import com.pms.governance.entity.StatutLivrable;

import java.time.LocalDate;

/**
 * WHAT IT IS
 * An immutable record used only for reading. Its component names are the JSON
 * keys the browser receives, so this file is the real contract with the Angular
 * "Livrable" interface: rename a component here and the matching field in the
 * front end becomes undefined.
 *
 * WHY IT IS FLAT
 * The entity has a nested Project; this record has projectId and projectCode as
 * two plain fields. LivrableMapper fills them with two rules: target
 * "projectId" comes from source "project.id", and target "projectCode" comes
 * from source "project.code" (the two MapStruct @Mapping lines). Deciding here,
 * once, how much of Project is exposed is safer than letting every screen
 * receive the whole object.
 */
public record LivrableResponse(
        // Primary key. The front end needs it to build the follow-up URLs, for
        // example PATCH /api/projects/7/livrables/42/valider, and to track rows
        // in the table.
        Long id,

        // Filled by MapStruct from project.id.
        // Without that @Mapping line MapStruct would look for a getProjectId() on
        // Livrable, not find one, leave the field null and only print a build
        // warning - so the mistake would appear in the browser, not at compile
        // time.
        Long projectId,

        // Filled by MapStruct from project.code: the short human-readable project
        // code. It lets a list that shows deliverables of several projects (a
        // dashboard, an export) print where each line belongs without calling
        // /api/projects/{id} again.
        String projectCode,

        String titre,

        // Can be null, exactly like the nullable column, which is why the Angular
        // interface declares "description?: string".
        String description,

        // A calendar day with no time and no time zone. Jackson writes it as
        // "2026-09-18".
        // Why not a timestamp: a deadline is a day. With a timestamp the value
        // would be converted to the reader's time zone and a deliverable due on
        // the 30th could be displayed as the 29th for a user one zone behind.
        // May be null when no date has been agreed yet.
        LocalDate dateEcheance,

        // Where the deliverable stands in the state machine
        // EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE.
        // Sent as the enum name, so Jackson writes "statut": "LIVRE". That exact
        // string matches the TypeScript union type StatutLivrable and the
        // Transloco translation key used to label the badge. governance.component.ts
        // also drives the action buttons from it: the "Valider" button is shown
        // only when this value is LIVRE - the same rule LivrableService.valider()
        // enforces on the server, so the screen never offers a move the API would
        // refuse with a BusinessRuleException.
        StatutLivrable statut
) {}

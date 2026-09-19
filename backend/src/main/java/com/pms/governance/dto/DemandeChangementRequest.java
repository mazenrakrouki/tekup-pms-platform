package com.pms.governance.dto;

/*
 * WHAT THIS FILE IS
 * The shape of the JSON body a client sends when it opens or edits a change
 * request ("demande de changement") on a project: someone asks for a change in
 * the scope, and the project manager then approves or rejects it.
 * DTO = Data Transfer Object: a small object whose only job is to carry data
 * between the browser and the server, so the database entity never leaves the
 * backend.
 *
 * WHERE IT SITS IN THE FLOW
 * Browser (POST or PUT /api/projects/{projectId}/demandes-changement)
 *   -> DemandeChangementController, which declares
 *      "@Valid @RequestBody DemandeChangementRequest". Jackson builds this record
 *      from the JSON, then Bean Validation applies the rules below. @Valid is the
 *      switch: remove it from the controller parameter and every annotation in
 *      this file stops working, silently.
 *   -> DemandeChangementService.create() / update(), which read request.titre(),
 *      request.demandeurId(), ... and copy them into the DemandeChangement
 *      entity. The service also turns demandeurId into a real User through
 *      userRepository, and answers 404 if that user does not exist or is deleted.
 *   -> DemandeChangementRepository saves the row in table "demandes_changement"
 *      (migration V11).
 * The answer comes back as DemandeChangementResponse.
 *
 * WHY IT EXISTS
 * This record is what makes the approval step mean something. A change request
 * follows EN_ATTENTE -> APPROUVE or REJETE, and only PATCH .../approuver and
 * PATCH .../rejeter can move it (both check MANAGE_GOVERNANCE and both refuse a
 * request that was already decided). There is deliberately no "statut" and no
 * "dateDecision" here. If the controller accepted the entity instead, the person
 * asking for the change could send {"statut": "APPROUVE"} in a plain PUT and
 * approve their own request - the whole governance rule would be decoration.
 */

import com.pms.governance.entity.PrioriteChangement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * WHAT IT IS
 * A Java "record": a short, immutable data holder. The compiler generates the
 * constructor, the accessors demandeurId(), titre(), priorite(), ... plus
 * equals(), hashCode() and toString().
 *
 * WHY A RECORD RATHER THAN A CLASS WITH SETTERS
 * No setter means the object approved by the validator is the exact object the
 * service reads. DemandeChangementService.update() copies five fields on five
 * separate lines; with a mutable object a value could be changed in between and
 * the saved row would not match what was validated.
 *
 * WHAT IS DELIBERATELY ABSENT
 * - statut and dateDecision: set by the server only, in approuver() / rejeter().
 * - projectId: it comes from the URL, where ProjectScopeInterceptor (ADR-021)
 *   checks it against the caller's project scope before the controller runs.
 *   If the project id also came from the body, a caller could pass the scope
 *   check with a project it owns and file the change request against another.
 */
public record DemandeChangementRequest(
        // Database id of the user who asks for the change.
        // @NotNull refuses a missing or null value.
        // Why the id and not the whole user: the client must not be able to
        // invent a name; the service looks the id up with
        // userRepository.findById(...), filters out deleted users and answers 404
        // if nothing matches, so the request is always attached to a real account.
        // Without @NotNull: null would reach findById(null) and come back as a
        // 500 server error instead of a clean 400 pointing at the field.
        @NotNull Long demandeurId,

        // Short summary of the change, the line shown in the register.
        // @NotBlank refuses null, "" and a value made only of spaces.
        // Why: the column "titre" is VARCHAR(255) NOT NULL in V11.
        // Without it: a change request is saved with the title " ", and the
        // approver sees a row with nothing to decide on.
        @NotBlank String titre,

        // The full explanation of the change. Optional on purpose: the column is
        // VARCHAR(1000) and nullable, because an urgent request is often opened
        // with a title first and filled in afterwards.
        String description,

        // How urgent the change is: FAIBLE, NORMALE, ELEVEE or CRITIQUE.
        // Note that this scale is its own enum, not the three-level NiveauRisque
        // used for risks and stakeholders: it has four values and the high one is
        // spelled ELEVEE, not ELEVE. Mixing the two would break the database
        // check constraint chk_dc_priorite of V11.
        // Why an enum and not a String: a wrong value such as "URGENT" is refused
        // by Jackson at the door with a 400; as a String it would travel to
        // Postgres and come back as an unreadable 500 constraint error.
        // Why @NotNull even though the entity defaults to NORMALE: the service
        // always calls .priorite(request.priorite()), so a null in the body would
        // overwrite that default with null and break the NOT NULL column.
        @NotNull PrioriteChangement priorite,

        // The day the change was asked for. LocalDate is a calendar day with no
        // time and no time zone; Jackson reads and writes it as "2026-09-18".
        // Why LocalDate and not a timestamp: this date is compared with contract
        // dates and shown in reports, and a timestamp would be shifted to the
        // reader's time zone - a request filed on the 1st could be displayed as
        // the 31st of the previous month, and land in the wrong monthly report.
        // @NotNull because the column date_demande is NOT NULL: this is the date
        // that proves when the change entered the process.
        @NotNull LocalDate dateDemande
) {}

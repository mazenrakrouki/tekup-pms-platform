package com.pms.governance.dto;

/*
 * WHAT THIS FILE IS
 * The shape of the JSON the API sends back for one change request
 * ("demande de changement"). It is the read side, the mirror of
 * DemandeChangementRequest, and it is the richest response of the governance
 * module because it flattens two relations, not one: the project and the person
 * who asked for the change.
 *
 * WHERE IT SITS IN THE FLOW
 * DemandeChangementRepository loads DemandeChangement entities
 *   -> DemandeChangementMapper.toResponse() / toResponseList() turn them into
 *      this record. MapStruct (ADR-018) generates that mapper at compile time
 *      into a class named DemandeChangementMapperImpl.
 *   -> DemandeChangementService returns it - including from approuver() and
 *      rejeter(), so the screen receives the new statut and the new dateDecision
 *      in the same answer and does not need a second GET.
 *   -> DemandeChangementController wraps it in ResponseEntity
 *   -> Jackson turns it into JSON
 *   -> Angular reads it as the "DemandeChangement" interface in
 *      core/models/governance.model.ts.
 *
 * WHY IT EXISTS
 * Without it the controller would return the DemandeChangement entity, and:
 * 1) the entity holds two lazy relations,
 *    "@ManyToOne(fetch = FetchType.LAZY) private Project project" and
 *    "@ManyToOne(fetch = FetchType.LAZY) private User demandeur". Lazy means
 *    they are loaded only when something asks for them; Jackson asks for
 *    everything, so the call would either fail with a LazyInitializationException
 *    once the transaction is closed, or pull whole object graphs into the JSON.
 * 2) the User graph is the dangerous one: it would publish the account's email,
 *    its password hash, its tokenVersion and its role. Exposing the hash turns a
 *    simple read endpoint into a password-cracking gift. This record sends only
 *    the id and the display name.
 */

import com.pms.governance.entity.PrioriteChangement;
import com.pms.governance.entity.StatutChangement;

import java.time.LocalDate;

/**
 * WHAT IT IS
 * An immutable record used only for reading. Its component names are the JSON
 * keys the browser receives, so this file is the real contract with the Angular
 * "DemandeChangement" interface.
 *
 * WHY IT IS FLAT
 * The entity has nested Project and User objects; this record has plain scalar
 * fields instead. DemandeChangementMapper fills them with four @Mapping lines
 * (project.id, project.code, demandeur.id, and the demandeur itself passed
 * through a small helper). Deciding here, in one visible list, how much of
 * Project and User is published is exactly what stops the accidental leak
 * described above.
 */
public record DemandeChangementResponse(
        // Primary key. The front end uses it to build the next URLs, for example
        // PATCH /api/projects/7/demandes-changement/42/approuver, and to track
        // rows in the table.
        Long id,

        // Filled by MapStruct from project.id.
        // Without that @Mapping line MapStruct would look for a getProjectId() on
        // the entity, not find one, leave the field null and only print a build
        // warning - the mistake would show up in the browser, not at compile time.
        Long projectId,

        // Filled by MapStruct from project.code: the short readable project code,
        // so a view that lists change requests across projects can show where each
        // one belongs without calling /api/projects/{id} again.
        String projectCode,

        // Filled by MapStruct from demandeur.id. Sent next to the name so the UI
        // can link to the person's page, or pre-select them in the edit form, with
        // the same id it must send back in DemandeChangementRequest.demandeurId().
        Long demandeurId,

        // The person's display name, ready to print.
        // HOW IT IS BUILT: the mapper declares
        // @Mapping(target = "demandeurFullName", source = "demandeur",
        //          qualifiedByName = "fullName")
        // which routes the whole User object through the helper method
        // fullName(User) marked @Named("fullName"). That helper returns
        // user.getFullName() - first name plus last name - and returns null when
        // the user is null instead of crashing.
        // WHY THE NAME IS SENT AT ALL: without it the browser would receive only
        // demandeurId and would have to call /api/users/{id} for every row. Twenty
        // change requests would mean twenty extra HTTP calls (the "N+1 calls"
        // problem) just to display twenty names.
        // WHY ONLY THE NAME: see the file header - sending the User object would
        // publish the email, the password hash and the role as well.
        String demandeurFullName,

        String titre,

        // May be null, like the nullable column, which is why the Angular
        // interface declares it "description?: string".
        String description,

        // How urgent: FAIBLE, NORMALE, ELEVEE or CRITIQUE. Note the four-value
        // scale, and ELEVEE with a final E - it is not the three-value
        // NiveauRisque used by risks and stakeholders.
        PrioriteChangement priorite,

        // Where the request stands: EN_ATTENTE, APPROUVE or REJETE.
        // Sent as the enum name, so Jackson writes "statut": "APPROUVE". That
        // exact string matches the TypeScript union type StatutChangement and the
        // Transloco key used to translate the badge. It also tells the screen
        // whether to show the Approve and Reject buttons at all - the server side
        // of the same rule is in DemandeChangementService, which throws a
        // BusinessRuleException when a request that is no longer EN_ATTENTE is
        // decided, edited or deleted.
        StatutChangement statut,

        // The day the change was asked for. A calendar day with no time and no
        // time zone, written as "2026-09-18", so it cannot drift by one day
        // between users in different time zones.
        LocalDate dateDemande,

        // The day the request was approved or rejected. Null while the status is
        // still EN_ATTENTE - the service sets it with LocalDate.now() at the same
        // moment it sets the status. The pair (statut, dateDecision) is the audit
        // trail of the decision: without this field the register would say a
        // change was approved but never say when.
        LocalDate dateDecision
) {}

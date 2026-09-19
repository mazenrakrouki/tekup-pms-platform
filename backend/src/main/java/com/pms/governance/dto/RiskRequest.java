package com.pms.governance.dto;

/*
 * WHAT THIS FILE IS
 * The shape of the JSON body a client sends when it creates or edits a risk
 * in the risk register of one project.
 * DTO = Data Transfer Object: a small object whose only job is to carry data
 * between the browser and the server, so that the database entity itself never
 * leaves the backend.
 *
 * WHERE IT SITS IN THE FLOW
 * Browser (POST or PUT /api/projects/{projectId}/risks)
 *   -> RiskController, which declares "@Valid @RequestBody RiskRequest".
 *      Jackson (the JSON library) builds this record from the JSON text, then
 *      Bean Validation checks the rules written below.
 *   -> RiskService.create() / update(), which read the values with
 *      request.description(), request.probabilite(), ... and copy them into the
 *      Risk entity.
 *   -> RiskRepository saves the row in table "risks" (migration V11).
 * The answer travels back the other way as RiskResponse.
 *
 * WHY IT EXISTS
 * Without it the controller would have to accept the Risk entity directly.
 * A client could then send fields it must never control - id, projectId,
 * deleted, createdAt - and, for example, move an existing risk of project A
 * into project B, or hide a risk by sending "deleted": true.
 * This record carries only the five values a user is allowed to send.
 */

import com.pms.governance.entity.NiveauRisque;
import com.pms.governance.entity.StatutRisque;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * WHAT IT IS
 * A Java "record": a short way to declare a data holder that cannot be changed
 * after it is built. The compiler writes the constructor, the accessors
 * (description(), probabilite(), ...), equals(), hashCode() and toString().
 *
 * WHY A RECORD RATHER THAN A NORMAL CLASS WITH SETTERS
 * A record has no setter, so the object the validator approved is exactly the
 * object the service reads. Example: RiskService.update() reads
 * request.description() and request.statut() on two different lines. With a
 * mutable class some other code could change the object between those two
 * lines, and the row written to the database would no longer match what was
 * validated.
 *
 * WHAT IS DELIBERATELY ABSENT
 * projectId is not a field here. It comes from the URL
 * /api/projects/{projectId}/risks, and ProjectScopeInterceptor (ADR-021)
 * checks that URL id against the caller's project scope before the controller
 * even runs. If the project id also arrived in the body, a caller could pass
 * the scope check with a project it owns in the URL and write the row into a
 * project it must not see.
 */
public record RiskRequest(
        // @NotBlank rejects null, "" and a value made only of spaces.
        // Why: the column "description" is VARCHAR(1000) NOT NULL in V11, and a
        // risk with no text is useless in the register.
        // Without it: a user saves a risk with description " ", the database
        // accepts it, and the risk table shows an empty row nobody can read.
        @NotBlank String description,

        // How likely the risk is: FAIBLE, MOYEN or ELEVE (the NiveauRisque enum).
        // @NotNull rejects a missing or null value.
        // Why an enum and not a free String: the database has
        // CHECK (probabilite IN ('FAIBLE','MOYEN','ELEVE')). With an enum, a bad
        // value such as "TRES_ELEVE" is refused by Jackson at the door with a
        // 400 answer; with a String it would reach Postgres and come back as an
        // ugly 500 constraint error.
        // Why @NotNull is still needed: the Risk entity gives probabilite the
        // default MOYEN, but RiskService.create() always calls
        // .probabilite(request.probabilite()). A null in the body would
        // overwrite that default with null and the insert would fail on the
        // NOT NULL column.
        @NotNull NiveauRisque probabilite,

        // How bad it would be if the risk happens: same three levels.
        // Probability and impact are kept as two separate fields, not multiplied
        // into one score, so the screen can show the classic probability/impact
        // grid and the user can see which of the two is the problem.
        @NotNull NiveauRisque impact,

        // The action planned to reduce the risk. No annotation: the column
        // plan_mitigation is nullable in V11, because a risk is often recorded
        // first and treated later.
        String planMitigation,

        // Where the risk stands: OUVERT, MITIGE or FERME.
        // Note the difference with Livrable and DemandeChangement: those two have
        // a state machine and their status is NOT part of the request body. A
        // risk has no forced order (it can go back from FERME to OUVERT if it
        // comes back), so RiskService.update() copies this value straight into
        // the entity.
        // Without @NotNull: a PUT that forgets "statut" would set the column to
        // null and break the NOT NULL constraint at flush time.
        @NotNull StatutRisque statut
) {}

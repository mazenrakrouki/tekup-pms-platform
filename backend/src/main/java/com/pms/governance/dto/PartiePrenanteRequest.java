package com.pms.governance.dto;

/*
 * WHAT THIS FILE IS
 * The shape of the JSON body a client sends when it adds or edits a stakeholder
 * ("partie prenante") in the stakeholder register of a project: a person or an
 * organisation the project has to deal with - a client contact, a supplier, an
 * internal sponsor.
 * DTO = Data Transfer Object: a small object whose only job is to carry data
 * between the browser and the server, so the database entity never leaves the
 * backend.
 *
 * WHERE IT SITS IN THE FLOW
 * Browser (POST or PUT /api/projects/{projectId}/parties-prenantes)
 *   -> PartiePrenanteController, which declares
 *      "@Valid @RequestBody PartiePrenanteRequest". Jackson builds this record
 *      from the JSON, then Bean Validation applies the rules below. @Valid is the
 *      switch: without it on the controller parameter, every annotation in this
 *      file would be ignored and bad data would go straight to the database.
 *   -> PartiePrenanteService.create() / update(), which read request.nom(),
 *      request.email(), ... and copy them into the PartiePrenante entity.
 *   -> PartiePrenanteRepository saves the row in table "parties_prenantes"
 *      (migration V11).
 * The answer comes back as PartiePrenanteResponse.
 *
 * WHY IT EXISTS
 * Without it the controller would accept the PartiePrenante entity itself, and a
 * client could send id, projectId or deleted. Example: a PUT carrying
 * "projectId": 3 would move a stakeholder of project 9 into project 3, even
 * though ProjectScopeInterceptor (ADR-021) had only checked project 9 from the
 * URL. Keeping the body to these six user-owned fields closes that hole.
 */

import com.pms.governance.entity.NiveauRisque;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * WHAT IT IS
 * A Java "record": a short, immutable data holder. The compiler generates the
 * constructor, the accessors nom(), fonction(), email(), ... plus equals(),
 * hashCode() and toString().
 *
 * WHY A RECORD RATHER THAN A CLASS WITH SETTERS
 * No setter means the object the validator approved is the exact object the
 * service reads later. PartiePrenanteService.update() copies six fields one line
 * at a time; with a mutable object something could change a field in between,
 * and the saved row would not be the row that was validated.
 *
 * WHAT IS DELIBERATELY ABSENT
 * projectId: it comes from the URL, where ProjectScopeInterceptor (ADR-021)
 * checks it against the caller's scope before the controller runs.
 */
public record PartiePrenanteRequest(
        // @NotBlank refuses null, "" and a value made only of spaces.
        // Why: "nom" is VARCHAR(255) NOT NULL in V11 and it is the only column
        // that identifies the person in the list.
        // Without it: a stakeholder is saved with the name " ", and the register
        // shows an anonymous line that cannot be told apart from another one.
        @NotBlank String nom,

        // Job or role of the person on the project ("Directeur SI", "Sponsor").
        // No annotation: the column is nullable, because a name and a contact are
        // often known before the exact title is.
        String fonction,

        // @Email checks the shape of the address (something before the @,
        // something after it). It is a format check only, not proof that the
        // mailbox exists.
        // Important: in Bean Validation, @Email and @Size both accept null. So
        // the address stays optional, which matches the nullable column, but as
        // soon as a value is present it must look like an address.
        // Without @Email: "jean.dupont" is stored, and later any mail merge or
        // export built from this register fails or silently drops the contact.
        //
        // @Size(max = 255) mirrors the column email VARCHAR(255) of V11.
        // Why: Hibernate does not cut the text. Without this rule a 400-character
        // address would travel all the way to Postgres, which answers "value too
        // long for type character varying(255)"; the user would get a 500 server
        // error instead of a clean 400 with a readable message on the field.
        @Email @Size(max = 255) String email,

        // Phone number kept as free text, not as a number: numbers may start with
        // 0 or +, and may contain spaces - "+216 71 000 000". Storing that in a
        // numeric type would lose the leading + and the leading 0.
        // Optional, like the column (VARCHAR(50), nullable).
        String telephone,

        // How much power this person has over the project: FAIBLE, MOYEN or ELEVE.
        //
        // Why reuse the NiveauRisque enum here, in a file that has nothing to do
        // with risk: influence and interest use exactly the same three-level
        // scale, and the database proves it - chk_pp_influence and chk_pp_interet
        // in V11 allow the same three values as the risk columns. One shared enum
        // means one shared set of Transloco labels (riskLevel.FAIBLE, ...) and no
        // second list to keep in step.
        //
        // @NotNull is needed even though the entity declares a default of MOYEN,
        // because PartiePrenanteService.create() always calls
        // .influence(request.influence()). A null in the body would overwrite the
        // default with null and the insert would fail on the NOT NULL column.
        @NotNull NiveauRisque influence,

        // How much this person cares about the project, on the same scale.
        // Influence and interest are kept as two separate values, not merged into
        // one score, because together they form the classic power/interest grid
        // that tells the project manager whom to manage closely and whom to just
        // keep informed. Merged into one number, that advice disappears.
        @NotNull NiveauRisque interet
) {}

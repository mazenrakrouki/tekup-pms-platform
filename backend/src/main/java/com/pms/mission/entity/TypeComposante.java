package com.pms.mission.entity;

/**
 * The kind of cost that a mission cost line holds (per diem, plane ticket, stamp/tax,
 * transport, stay). It is the "type_composante" column of the table composantes_mission.
 *
 * Where it sits in the flow: the Angular form sends one of these five words in the JSON
 * body -> ComposanteRequest (the DTO, i.e. the plain object that carries the request body)
 * -> ComposanteService -> ComposanteMission.typeComposante -> the database column ->
 * back out through ComposanteMapper inside ComposanteResponse.
 *
 * Why it exists: without this enum the type would be a free String, and the application
 * would happily store "perdim", "Per Diem" and "PERDIEM" as three different kinds of cost.
 * Totals per kind would then be wrong, and the list could no longer be grouped. With an
 * enum, a wrong word is rejected by Jackson at the door (HTTP 400) before any code runs.
 *
 * Why exactly these five values: they are the cost items the company actually reimburses
 * for a business trip. The same five words are repeated in the database rule
 * chk_comp_type (migration V10), so the database refuses anything else even if a row is
 * inserted by hand outside the application.
 *
 * Careful when changing this file: the values are stored as TEXT (see @Enumerated in
 * ComposanteMission). Renaming PERDIEM into PER_DIEM would break every existing row - the
 * old text could no longer be read back into the enum, and an INSERT of the new text would
 * be refused by chk_comp_type until a matching SQL migration changes the rule too.
 * Adding a new value needs a new migration; the order of the lines below carries no
 * meaning, because nothing stores the position number.
 */
public enum TypeComposante {
    PERDIEM,
    BILLET,
    TIMBRE,
    TRANSPORT,
    SEJOUR
}

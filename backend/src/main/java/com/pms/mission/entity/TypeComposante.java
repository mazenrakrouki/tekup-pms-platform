package com.pms.mission.entity;

/**
 * Kind of cost a mission cost line holds (per diem, ticket, stamp, transport, stay) — the
 * "type_composante" column of composantes_mission. An enum instead of a free String so Jackson
 * rejects a bad word at the door (400) rather than letting "perdim"/"Per Diem"/"PERDIEM" split
 * totals into three groups; DB rule chk_comp_type (V10) repeats the same five values.
 *
 * Stored as TEXT (see @Enumerated on ComposanteMission): renaming a value breaks existing rows,
 * and a new value needs a matching SQL migration for chk_comp_type. Declaration order carries
 * no meaning — nothing stores the ordinal position.
 */
public enum TypeComposante {
    PERDIEM,
    BILLET,
    TIMBRE,
    TRANSPORT,
    SEJOUR
}

/*
 * FILE: mission.model.ts
 *
 * WHAT THIS FILE IS
 * The two shapes of the mission module on the browser side: a mission (a business trip
 * made by one person for one project) and a "composante", one cost item of that trip.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot MissionController answers with the Java records MissionResponse and
 *   ComposanteResponse
 *     -> Jackson turns them into JSON
 *     -> core/services/mission.service.ts declares its Observables with the shapes below
 *     -> features/missions/missions.component.ts (the missions screen) and
 *        features/projects/project-detail/project-detail.component.ts display them.
 *
 * WHY IT EXISTS
 * It is the written contract between the Angular screens and the Java API. TypeScript
 * types are erased at compile time, so this file costs nothing at run time; what it buys
 * is checking while the project is being built. Without it the screens would type these
 * rows as 'any', and 'any' switches the compiler off: reading 'm.dateDepart' instead of
 * 'm.dateDebut' would compile, come back undefined, and the trip would be printed with an
 * empty start date.
 *
 * WHY A MISSION AND ITS COSTS ARE TWO SEPARATE SHAPES
 * One trip has several cost items of different kinds, and they are loaded on demand, only
 * for the trip the user opened. Nesting them inside Mission would force the list screen to
 * download every cost of every trip just to print a table of destinations.
 *
 * SECURITY REMINDER
 * Nothing here protects anything; it only describes data. On the server, reading needs
 * VIEW_MISSION and writing needs MANAGE_MISSION, checked with @PreAuthorize on the SERVICE
 * methods; and because every URL looks like /api/projects/{id}/missions...,
 * ProjectScopeInterceptor also checks that this user may touch THAT project (ADR-021).
 */

// A "union type" lists the only texts the field may hold - here the five kinds of cost a
// trip can carry, exactly as the Java enum TypeComposante spells them:
//   PERDIEM   = daily allowance paid to the traveller
//   BILLET    = plane or train ticket
//   TIMBRE    = stamps and administrative fees (visa, official papers)
//   TRANSPORT = local transport once there (taxi, car hire)
//   SEJOUR    = accommodation
// Why not a plain string: the screen picks an icon and a label from this value. A typo
// such as 'PER_DIEM' would compile as a string, match no icon, and the cost line would be
// drawn blank next to an amount - the reader could not tell what he is paying for.
export type TypeComposante = 'PERDIEM' | 'BILLET' | 'TIMBRE' | 'TRANSPORT' | 'SEJOUR';

/**
 * One business trip as the server SENDS it (mirror of the Java record MissionResponse).
 *
 * Note the two id + name pairs (projectId / projectCode, userId / userFullName). The id is
 * what identifies the row when the screen saves or deletes; the name is what it prints.
 * With the ids alone, the list would need one extra request per line just to show who
 * travelled and for which project.
 */
export interface Mission {
  id: number;
  projectId: number;
  projectCode: string;
  /** The person who travels. */
  userId: number;
  userFullName: string;
  /** Why the trip is made, in one sentence ("comite de pilotage chez le client"). */
  objet: string;
  /** Where it takes place ("Tunis", "Paris"). */
  lieu: string;
  /**
   * The first and the last day of the trip, as plain ISO text ("2026-07-14"). JSON has no
   * date type, so a Java LocalDate always arrives as a string; typing them as Date would
   * be a lie, and calling a Date method on them would crash the screen.
   * Both are required, with no "?": a trip with no dates could not be counted in any
   * period, and the daily allowance would have nothing to be computed from.
   */
  dateDebut: string;
  dateFin: string;
}

/**
 * One cost item of one trip (mirror of the Java record ComposanteResponse).
 *
 * Why the costs are split into typed lines instead of one total on the mission: the
 * accounting rules differ by kind - a daily allowance is a fixed rate, a ticket is a real
 * invoice - and the company has to be able to answer "how much did we spend on flights
 * this year". A single total could never be broken back down.
 */
export interface Composante {
  id: number;
  /** The trip this cost belongs to. It is in the URL as well, which is how the server can
   *  refuse straight away an id that belongs to another trip. */
  missionId: number;
  typeComposante: TypeComposante;
  montant: number;
  /**
   * The currency the amount is expressed in, as a short code ("TND", "EUR").
   * It is carried on EVERY line, not once on the trip, because one trip really does mix
   * currencies: the ticket is bought in euros and the taxi is paid in dinars. With one
   * currency for the whole trip, one of the two amounts would be counted wrong.
   */
  devise: string;
  /** Free text detail. Optional: "PERDIEM 3 jours" needs no explanation, a miscellaneous
   *  expense does. */
  description?: string;
}

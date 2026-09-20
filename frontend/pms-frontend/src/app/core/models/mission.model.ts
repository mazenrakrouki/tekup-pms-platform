// Mission (a business trip for one project) and Composante (one cost item of that trip),
// mirroring the Java records MissionResponse/ComposanteResponse. Kept as two shapes because
// costs are loaded on demand per trip, not nested and downloaded with every mission.

// The five cost kinds, mirroring the Java enum TypeComposante: PERDIEM (daily allowance),
// BILLET (ticket), TIMBRE (stamps/admin fees), TRANSPORT (local transport), SEJOUR (lodging).
export type TypeComposante = 'PERDIEM' | 'BILLET' | 'TIMBRE' | 'TRANSPORT' | 'SEJOUR';

/** One business trip as the server sends it (mirror of MissionResponse). Carries both id and
 *  name for project/user so the list can display without an extra request per row. */
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
  /** ISO date text ("2026-07-14"), not Date — a Java LocalDate always arrives as a string.
   *  Both required: a trip with no dates can't be placed in a period or costed. */
  dateDebut: string;
  dateFin: string;
}

/** One cost item of one trip (mirror of ComposanteResponse). Split into typed lines, not one
 *  total on the mission, because accounting rules differ by kind (fixed rate vs invoice). */
export interface Composante {
  id: number;
  /** The trip this cost belongs to; also in the URL, so the server can reject a mismatched id. */
  missionId: number;
  typeComposante: TypeComposante;
  montant: number;
  /** Short currency code ("TND", "EUR"), per line since one trip can mix currencies. */
  devise: string;
  /** Free text detail. Optional: "PERDIEM 3 jours" needs no explanation, a misc. expense does. */
  description?: string;
}

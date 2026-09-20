import { NiveauRisque } from './governance.model';

// A project stakeholder (client-side or internal person affected by / affecting the project).
// Kept out of governance.model.ts so the import below stays visible: influence/interet reuse
// the SAME NiveauRisque scale as the risk matrix, rather than a private copy that could drift.

/** One stakeholder of one project (mirror of the Java record PartiePrenanteResponse).
 *  influence and interet are separate fields so the person can be placed on the classic
 *  four-box grid (e.g. high influence + low interest = keep satisfied). */
export interface PartiePrenante {
  id: number;
  projectId: number;
  /** The readable project code, so the screen can name the project without a second call. */
  projectCode: string;
  nom: string;
  /** Job title, for example "Directeur des systemes d'information". */
  fonction?: string;
  /** Optional: a stakeholder is worth listing as soon as the name is known, often before
   *  contact details exist. */
  email?: string;
  telephone?: string;
  /** Weight this person has on project decisions. Shared scale with the risk matrix (see
   *  header) so colours mean the same thing on both screens. */
  influence: NiveauRisque;
  /** How much this person cares about the outcome. Same shared scale. */
  interet: NiveauRisque;
}

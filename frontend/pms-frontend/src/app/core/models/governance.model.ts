// Browser-side shapes for governance: a project's risks, deliverables (livrables), and
// change requests (demandes de changement). Mirrors the Java records RiskResponse/
// LivrableResponse/DemandeChangementResponse; governance.service.ts and
// governance.component.ts / project-detail are the consumers. Field names are in French,
// copied from the Java records and the company's own project documents - don't translate
// them, that would desync from the real JSON. NiveauRisque also lives here because
// partie-prenante.model.ts imports it rather than duplicating it. Nothing here enforces
// access: reading needs VIEW_GOVERNANCE, writing MANAGE_GOVERNANCE, checked via
// @PreAuthorize plus ProjectScopeInterceptor (ADR-021).

// Three-step scale matching Java's NiveauRisque; shared by risk probability/impact and
// stakeholder influence/interest so the colour scale stays consistent everywhere.
export type NiveauRisque = 'FAIBLE' | 'MOYEN' | 'ELEVE';
// OUVERT (open) -> MITIGE (mitigation plan in place) -> FERME (closed), dropdown order.
export type StatutRisque = 'OUVERT' | 'MITIGE' | 'FERME';
// EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE. The browser never sets these directly - see
// Livrable.statut below.
export type StatutLivrable = 'EN_ATTENTE' | 'EN_COURS' | 'LIVRE' | 'VALIDE';
// EN_ATTENTE -> APPROUVE or REJETE. A distinct type from StatutLivrable even though the
// first value is spelled the same, so the two can't be mixed up by the compiler.
export type StatutChangement = 'EN_ATTENTE' | 'APPROUVE' | 'REJETE';
export type PrioriteChangement = 'FAIBLE' | 'NORMALE' | 'ELEVEE' | 'CRITIQUE';

/**
 * One risk as the server SENDS it (mirror of RiskResponse). Probability and impact stay two
 * separate fields (not one "criticality" score) so the risk matrix can distinguish a likely
 * but harmless risk from an unlikely but fatal one.
 */
export interface Risk {
  id: number;
  projectId: number;
  /** Readable project code, so the screen can name the project without a second call. */
  projectCode: string;
  description: string;
  probabilite: NiveauRisque;
  impact: NiveauRisque;
  /** Optional: a risk is worth logging before a mitigation plan exists. */
  planMitigation?: string;
  statut: StatutRisque;
}

/**
 * One deliverable (mirror of LivrableResponse): a document, module or training session owed
 * to the client. Also feeds the KPI engine's deliveryPct indicator (see kpi.model.ts).
 */
export interface Livrable {
  id: number;
  projectId: number;
  projectCode: string;
  titre: string;
  description?: string;
  /** ISO date text; optional since a deliverable can be listed before a date is agreed. */
  dateEcheance?: string;
  /** The browser never sets this directly: governance.service.ts calls the demarrer /
   *  livrer / valider endpoints and the server enforces legal transitions only. */
  statut: StatutLivrable;
}

/**
 * One change request (mirror of DemandeChangementResponse): a formal demand to modify scope
 * once a project has started.
 */
export interface DemandeChangement {
  id: number;
  projectId: number;
  projectCode: string;
  /** The user who asked for the change. */
  demandeurId: number;
  demandeurFullName: string;
  titre: string;
  description?: string;
  priorite: PrioriteChangement;
  /** As with a deliverable, set only via the approuver / rejeter endpoints. */
  statut: StatutChangement;
  dateDemande?: string;
  /** ISO text; absent while still EN_ATTENTE, which lets the screen show pending duration. */
  dateDecision?: string;
}

/*
 * FILE: governance.model.ts
 *
 * WHAT THIS FILE IS
 * The shapes of the governance module on the browser side: the risks of a project, its
 * deliverables (livrables) and its change requests (demandes de changement). It holds only
 * TYPE information - no logic, and nothing that survives the compilation.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot answers with the Java records RiskResponse, LivrableResponse and
 *   DemandeChangementResponse
 *     -> Jackson turns them into JSON
 *     -> core/services/governance.service.ts declares its Observables with the shapes below
 *     -> features/governance/governance.component.ts (the governance screen) and
 *        features/projects/project-detail/project-detail.component.ts display them.
 *
 * WHY IT EXISTS
 * It is the written contract between the Angular screens and the Java API. TypeScript
 * types are erased at compile time, so this file adds nothing to what the browser
 * downloads; what it adds is checking while the project is being built. Without it the two
 * screens would type the same answers as 'any', and 'any' switches the compiler off:
 * reading 'r.statuts' instead of 'r.statut' would compile, arrive as undefined, and a risk
 * that is still open would be drawn with no badge at all.
 *
 * WHY THE FIELD NAMES ARE IN FRENCH
 * They are copied one for one from the Java records, which themselves follow the wording
 * of the company's own project documents. Translating them here would make these
 * interfaces stop describing the real JSON, and every read of a renamed field would give
 * undefined.
 *
 * IT IS ALSO THE HOME OF NiveauRisque
 * partie-prenante.model.ts imports NiveauRisque from this file instead of declaring its
 * own copy - see the note on that type below.
 *
 * SECURITY REMINDER
 * Nothing here protects anything; it only describes data. On the server, reading needs
 * VIEW_GOVERNANCE and writing needs MANAGE_GOVERNANCE, checked with @PreAuthorize on the
 * SERVICE methods; and because every governance URL looks like /api/projects/{id}/...,
 * ProjectScopeInterceptor also checks that this user may touch THAT project (ADR-021).
 */

// A "union type" lists the only texts a field may hold. This one is a three-step scale,
// low / medium / high, matching the Java enum NiveauRisque.
// It is used FOUR times: a risk has a probability and an impact, and a stakeholder has an
// influence and an interest. One shared type is what lets the screen use the same colour
// scale everywhere. Four separate copies would drift apart, and the same word would end up
// green on one screen and red on another.
// Why not a plain string: the screen picks a colour from this value; a typo such as
// 'ELEVEE' would match no colour and a critical risk would be drawn as if it were neutral.
export type NiveauRisque = 'FAIBLE' | 'MOYEN' | 'ELEVE';
// The life of a risk: OUVERT (open) -> MITIGE (a mitigation plan is in place) -> FERME
// (closed). The order is the order the screen offers in its dropdown.
export type StatutRisque = 'OUVERT' | 'MITIGE' | 'FERME';
// The life of a deliverable: EN_ATTENTE (not started) -> EN_COURS (being worked on) ->
// LIVRE (handed over) -> VALIDE (accepted by the client). These four values are what the
// four PATCH endpoints of governance.service.ts move between; the browser never sets them
// directly - see the note on Livrable.statut below.
export type StatutLivrable = 'EN_ATTENTE' | 'EN_COURS' | 'LIVRE' | 'VALIDE';
// The life of a change request: EN_ATTENTE (waiting for a decision) -> APPROUVE or REJETE.
// Note that EN_ATTENTE is spelled the same here and in StatutLivrable but the two are
// different types: TypeScript would refuse to put a StatutLivrable in a StatutChangement
// field, which is the point of declaring them separately.
export type StatutChangement = 'EN_ATTENTE' | 'APPROUVE' | 'REJETE';
// How urgent a change request is, from low to critical (Java enum PrioriteChangement).
export type PrioriteChangement = 'FAIBLE' | 'NORMALE' | 'ELEVEE' | 'CRITIQUE';

/**
 * One risk of a project, as the server SENDS it (mirror of the Java record RiskResponse).
 * A risk is something that has not happened yet but would hurt the project if it did.
 *
 * Why probability and impact are kept as two separate fields instead of one "criticality"
 * number: the pair is what the risk matrix is drawn from, and a single score cannot be
 * split back into its two halves. A risk that is very likely but harmless and one that is
 * unlikely but fatal would get the same score and be handled the same way, which is
 * exactly the mistake a risk matrix exists to prevent.
 */
export interface Risk {
  id: number;
  projectId: number;
  /** The readable project code, so the screen can name the project without a second call. */
  projectCode: string;
  description: string;
  /** How likely the risk is to happen. */
  probabilite: NiveauRisque;
  /** How much it would hurt if it happened. */
  impact: NiveauRisque;
  /**
   * What is planned to reduce the risk. "?" means it may be missing: a risk is worth
   * writing down as soon as it is spotted, long before anybody knows what to do about it.
   * Forcing a plan at creation would simply push people to type "TBD" and the register
   * would stop being trustworthy.
   */
  planMitigation?: string;
  statut: StatutRisque;
}

/**
 * One deliverable of a project (mirror of the Java record LivrableResponse). A deliverable
 * is something the company owes the client: a document, a module, a training session.
 *
 * Deliverables matter beyond this screen: the KPI engine counts how many are delivered
 * against how many are planned to produce the deliveryPct indicator (see kpi.model.ts).
 */
export interface Livrable {
  id: number;
  projectId: number;
  projectCode: string;
  titre: string;
  description?: string;
  /**
   * The due date, as plain ISO text ("2026-07-14"). JSON has no date type, so a Java
   * LocalDate always arrives as a string; typing it as Date would be a lie and any call to
   * a Date method on it would crash the screen.
   * Optional, because a deliverable can be listed before its date is agreed.
   */
  dateEcheance?: string;
  /**
   * The current step. The browser never chooses it directly: governance.service.ts calls
   * the named endpoints demarrer / livrer / valider, and the server moves the status
   * itself and refuses an illegal move.
   * Why it is done that way: with a plain edit, a screen could push a deliverable straight
   * from EN_ATTENTE to VALIDE, and the client would appear to have accepted something that
   * was never handed over.
   */
  statut: StatutLivrable;
}

/**
 * One change request (mirror of the Java record DemandeChangementResponse). A change
 * request is a formal demand to modify the scope of the project once it has started.
 *
 * Note the pair demandeurId + demandeurFullName: the id is what identifies the person, the
 * name is what is printed. Without the name the list would need one extra request per row
 * just to show who asked.
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
  /**
   * Waiting, approved or rejected. As with a deliverable, the browser does not set this
   * field: it calls the approuver / rejeter endpoints and the server decides.
   */
  statut: StatutChangement;
  /** When the change was asked for, as ISO text. */
  dateDemande?: string;
  /**
   * When it was approved or rejected, as ISO text. Missing while the request is still
   * EN_ATTENTE - which is precisely how the screen can show how long a decision has been
   * pending.
   */
  dateDecision?: string;
}

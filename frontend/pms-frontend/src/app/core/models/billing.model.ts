// Browser-side shapes for a project's finances: billing milestones (jalons), payments
// against them (paiements), and signed contract amendments (avenants). Mirrors the Java
// records JalonResponse/PaiementResponse/AvenantResponse; billing.service.ts and
// billing.component.ts / project-detail's money tab are the consumers. A milestone's life
// is PREVU (planned) -> FACTURE (invoiced) -> PAYE (paid), enforced server-side. Nothing
// here enforces access: reading needs VIEW_BILLING, writing MANAGE_BILLING, checked via
// @PreAuthorize plus ProjectScopeInterceptor (ADR-021).

// Union, not a plain string: the badge colour is picked from this value, so a typo must
// fail at build time rather than silently drawing a paid invoice as still planned.
export type JalonStatut = 'PREVU' | 'FACTURE' | 'PAYE';

/**
 * One billing milestone as the server SENDS it (mirror of JalonResponse) - a contract step
 * that can be invoiced, e.g. "30% on acceptance".
 */
export interface JalonFacturation {
  id: number;
  projectId: number;
  /** Readable project code, so the screen can name the project without a second call. */
  projectCode: string;
  label: string;
  /** Share of the project budget, as a percentage (30 means 30%) - the value a user types. */
  pourcentage: number;
  /** Money amount, computed once by the server (budget x pourcentage / 100) and frozen once
   *  invoiced, so an already-sent invoice never silently changes when the budget is revised. */
  montant: number;
  /** Forecast invoicing date, ISO text. Optional: a milestone can be agreed before a date is known. */
  datePrevue?: string;
  /** Date the invoice was actually issued; absent while still PREVU. */
  dateFacture?: string;
  statut: JalonStatut;
}

/**
 * One payment against ONE milestone (mirror of PaiementResponse). Linked to the milestone,
 * not the project, so the server can sum payments and flip the milestone to PAYE itself.
 */
export interface Paiement {
  id: number;
  jalonId: number;
  /** May be LESS than the milestone amount - a client can pay in instalments. */
  montantRecu: number;
  /** ISO date text, required. */
  datePaiement: string;
}

/**
 * One signed contract amendment (mirror of AvenantResponse): extra money, extra days, or
 * both, agreed with the client. Kept as its own row rather than just raising the budget, so
 * the revised budget stays explainable line by line.
 */
export interface Avenant {
  id: number;
  projectId: number;
  projectCode: string;
  /** Reference on the signed paper, e.g. "AV-2026-03". */
  numero: string;
  /** What was agreed, in one sentence. */
  objet: string;
  montant: number;
  /** Extra man-days sold; optional since some amendments only change scope. */
  workloadDays?: number;
  /** Signature date; required, since an unsigned amendment is not one. */
  dateAvenant: string;
}

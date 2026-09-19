/*
 * FILE: billing.model.ts
 *
 * WHAT THIS FILE IS
 * The shapes of the money side of a project, seen from the browser: the billing milestones
 * (jalons), the payments received against a milestone (paiements) and the signed contract
 * amendments (avenants). It holds only TYPE information - no logic, no value that survives
 * the compilation.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot BillingController answers with the Java records JalonResponse,
 *   PaiementResponse and AvenantResponse
 *     -> Jackson turns them into JSON
 *     -> core/services/billing.service.ts declares its Observables with the shapes below
 *     -> features/billing/billing.component.ts (the billing screen) and
 *        features/projects/project-detail/project-detail.component.ts (the money tab of
 *        one project) display them.
 *
 * WHY IT EXISTS
 * Two different screens read the same figures. Without this shared file each of them would
 * describe the answer its own way, and one spelling difference would be enough to show the
 * same amount twice with two different meanings - on accounting data, which is exactly the
 * kind of mistake nobody forgives. It also catches a wrong field name while the project is
 * being built, instead of printing an empty cell to the user.
 *
 * HOW A MILESTONE LIVES (needed to read the shapes below)
 *   PREVU (planned) -> FACTURE (invoiced) -> PAYE (paid).
 * The order is enforced on the server: a payment is refused while the milestone is still
 * PREVU, and the status moves to PAYE on its own once the payments cover the amount.
 *
 * SECURITY REMINDER
 * Nothing here protects anything; it only describes data. On the server, reading needs
 * VIEW_BILLING and writing needs MANAGE_BILLING, checked with @PreAuthorize on the SERVICE
 * methods; and because every billing URL looks like /api/projects/{id}/...,
 * ProjectScopeInterceptor also checks that this user may touch THAT project (ADR-021).
 */

// A "union type" lists the only texts the field may hold, here the three states of a
// milestone, exactly as the Java enum JalonStatut spells them.
// Why not a plain string: the screen chooses the colour of the badge from this value. With
// a plain string, a typo such as 'PAYEE' would compile, no badge would match, and an
// invoice that is really paid would be drawn as if it were still planned.
export type JalonStatut = 'PREVU' | 'FACTURE' | 'PAYE';

/**
 * One billing milestone as the server SENDS it (mirror of the Java record JalonResponse).
 * A milestone is one step of the contract that can be invoiced, for example
 * "30% on acceptance".
 */
export interface JalonFacturation {
  id: number;
  projectId: number;
  /** The readable project code, so the screen can name the project without a second call. */
  projectCode: string;
  label: string;
  /**
   * The share of the project budget this milestone represents, as a percentage
   * (30 means 30%). This is the value a user types in; the amount below is not.
   */
  pourcentage: number;
  /**
   * The amount in money. It is NOT typed in by anyone: the server computes it once, as
   * "effective budget of the project x pourcentage / 100", and freezes it as soon as the
   * milestone leaves PREVU.
   * Why it is sent instead of being recomputed here: the browser does not know the
   * effective budget of the project on every screen, and two places computing money is two
   * places that can disagree. Frozen after invoicing, because an invoice already sent to
   * the client must never change by itself when the budget is revised.
   */
  montant: number;
  /**
   * The date the milestone is EXPECTED to be invoiced - a forecast used to build the cash
   * plan. "?" means it may be missing, because a milestone can be agreed ("30% on
   * acceptance") long before anybody can say on which day acceptance will happen.
   * It is a plain string in the ISO form "2026-07-14": JSON has no date type, so a Java
   * LocalDate always arrives as text. Typing it as Date would be a lie and any call to a
   * Date method on it would crash the screen.
   */
  datePrevue?: string;
  /**
   * The date the invoice was really issued. Missing while the status is still PREVU.
   * It is what lets an auditor match this row with the paper invoice; without it a
   * milestone could sit in status FACTURE with no invoice date at all.
   */
  dateFacture?: string;
  statut: JalonStatut;
}

/**
 * One payment received against ONE milestone (mirror of the Java record PaiementResponse).
 *
 * Why a payment hangs under a milestone and not under the project: a payment has no
 * meaning on its own, it always settles one invoice. Keeping the link explicit is what
 * lets the server add up the payments of a milestone and move it to PAYE by itself.
 */
export interface Paiement {
  id: number;
  jalonId: number;
  /**
   * The amount actually received, which may be LESS than the milestone amount: a client
   * can pay in several instalments. This is why the milestone does not simply flip to PAYE
   * on the first payment - the server compares the sum of the payments with the amount.
   */
  montantRecu: number;
  /** The day the money arrived, as ISO text ("2026-07-14"). Required, never missing. */
  datePaiement: string;
}

/**
 * One signed amendment to the contract (mirror of the Java record AvenantResponse).
 * An "avenant" is a change agreed with the client: extra money, extra days of work, or
 * both.
 *
 * Why amendments are kept as their own rows instead of simply raising the project budget:
 * the revised budget of a project is the initial budget plus its amendments, and that sum
 * must stay explainable line by line in front of the client. With only a raised budget,
 * nobody could say afterwards where the extra money came from.
 */
export interface Avenant {
  id: number;
  projectId: number;
  projectCode: string;
  /** The reference written on the signed paper, for example "AV-2026-03". */
  numero: string;
  /** What was agreed, in one sentence. */
  objet: string;
  /** The money added by this amendment. */
  montant: number;
  /**
   * The extra man-days sold by this amendment. Optional, because some amendments only add
   * time or only change the scope, with no extra money attached to days.
   */
  workloadDays?: number;
  /** The signature date, as ISO text. Required: an unsigned amendment is not an amendment. */
  dateAvenant: string;
}

import { NiveauRisque } from './governance.model';

/*
 * FILE: partie-prenante.model.ts
 *
 * WHAT THIS FILE IS
 * One single shape, PartiePrenante, describing a stakeholder of a project: a person on the
 * client side or inside the company who can affect the project or is affected by it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot answers with the Java record PartiePrenanteResponse
 *     -> Jackson turns it into JSON
 *     -> core/services/governance.service.ts uses this shape in listParties(),
 *        createPartie() and deletePartie()
 *     -> features/governance/governance.component.ts draws the stakeholder table and the
 *        influence / interest grid.
 *
 * WHY IT IS A FILE OF ITS OWN AND NOT PART OF governance.model.ts
 * A stakeholder is a person, not an event of the project like a risk, a deliverable or a
 * change request. Keeping it apart is what makes the import line above visible: a reader
 * sees at once that the stakeholder grid reuses the SAME scale as the risk matrix, instead
 * of having a second three-step scale that happens to be spelled the same way today and
 * could drift tomorrow.
 *
 * WHY IT EXISTS AT ALL
 * Without it, governance.service.ts would type these rows as 'any', and 'any' switches the
 * compiler off: reading 'p.telephone' when the field had been renamed would compile, come
 * back undefined, and the contact column of the table would silently print nothing.
 *
 * SECURITY REMINDER
 * Nothing here protects anything; it only describes data. On the server, reading needs
 * VIEW_GOVERNANCE and writing needs MANAGE_GOVERNANCE, checked with @PreAuthorize on the
 * SERVICE methods; and because the URL is /api/projects/{id}/parties-prenantes,
 * ProjectScopeInterceptor also checks that this user may touch THAT project (ADR-021).
 */

/**
 * One stakeholder of one project (mirror of the Java record PartiePrenanteResponse).
 *
 * WHY influence AND interet ARE TWO SEPARATE FIELDS
 * Together they place the person on the classic four-box grid: high influence + high
 * interest = manage closely; high influence + low interest = keep satisfied; and so on.
 * A single "importance" score could not be split back into its two halves, and a powerful
 * but indifferent client director would end up treated like an enthusiastic end user.
 */
export interface PartiePrenante {
  id: number;
  projectId: number;
  /** The readable project code, so the screen can name the project without a second call. */
  projectCode: string;
  nom: string;
  /** Job title, for example "Directeur des systemes d'information". */
  fonction?: string;
  /**
   * The "?" on these three fields means the value may be missing from the JSON.
   * That is deliberate: a stakeholder is worth listing as soon as his name is known, often
   * before anybody has his contact details. Making them required would push people to type
   * a fake e-mail just to save the row, and the register would stop being trustworthy.
   */
  email?: string;
  telephone?: string;
  /**
   * How much weight this person has on the decisions of the project.
   * The type comes from governance.model.ts on purpose - see the header above. The same
   * three-step scale is shared with the risk matrix, so the colours mean the same thing on
   * both screens. Declaring a private copy here would compile today and drift the day a
   * fourth level is added to one of the two.
   */
  influence: NiveauRisque;
  /** How much this person cares about the outcome of the project. Same shared scale. */
  interet: NiveauRisque;
}

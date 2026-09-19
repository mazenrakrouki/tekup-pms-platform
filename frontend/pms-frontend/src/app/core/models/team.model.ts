/*
 * FILE: team.model.ts
 *
 * WHAT THIS FILE IS
 * The two shapes of the project team on the browser side: one assignment as the server
 * sends it (TeamAssignment) and the body sent when somebody is added to a team
 * (TeamAssignmentRequest). An "assignment" is the link between one person and one project,
 * over a period of time.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot TeamAssignmentService answers with the Java record TeamAssignmentResponse
 *     -> Jackson turns it into JSON
 *     -> core/services/team.service.ts declares list(), assign() and remove() with the
 *        shapes below
 *     -> features/projects/project-detail/project-detail.component.ts (the team tab) and
 *        features/agile/agile.component.ts (to fill the "assign to" dropdown of a backlog
 *        item) display them.
 *
 * WHY IT EXISTS
 * It is the written contract between the Angular screens and the Java API. Without it the
 * two screens would type these rows as 'any', and 'any' switches the compiler off: reading
 * 'a.userName' instead of 'a.userFullName' would compile, come back undefined, and the
 * team table would print a column of empty names.
 *
 * WHY AN ASSIGNMENT IS A ROW OF ITS OWN AND NOT A LIST OF USERS ON THE PROJECT
 * Because it carries dates. The same person can join a project in March, leave in June and
 * come back in September, and the workload figures have to know which period is which. A
 * plain list of members could not say when anybody was there.
 *
 * SECURITY REMINDER
 * Nothing here protects anything; it only describes data. On the server, reading needs
 * VIEW_TEAM and adding or removing a member needs ASSIGN_DEVELOPER, checked with
 * @PreAuthorize on the SERVICE methods; and because the URL is /api/projects/{id}/team,
 * ProjectScopeInterceptor also checks that this user may touch THAT project (ADR-021).
 */

/**
 * One person assigned to one project, as the server SENDS it (mirror of the Java record
 * TeamAssignmentResponse).
 *
 * Note that the answer carries BOTH the code and the name of the project. That is not
 * duplication: the same shape is read from the team tab of a project, where the project is
 * already known, and from screens that list the assignments of one person across several
 * projects, where neither is known. Sending both means neither screen needs a second call.
 */
export interface TeamAssignment {
  id: number;
  projectId: number;
  /** The short readable code, for example "S2I-2026-014". */
  projectCode: string;
  /** The full project name, for the screens that show assignments outside a project page. */
  projectName: string;
  /** The person. The id is what is sent back when saving; the name is what is printed. */
  userId: number;
  userFullName: string;
  /**
   * What the person does ON THIS PROJECT, as free text ("Developpeur back-end").
   * It has NOTHING to do with the security roles of rbac.model.ts, and nothing in the code
   * ever reads it to decide what is allowed. It is a label for humans. Confusing the two
   * is the mistake to avoid here: writing "ADMIN" in this field grants absolutely nothing.
   */
  roleInTeam: string;
  /**
   * The day the person joined the project, as plain ISO text ("2026-07-14"). JSON has no
   * date type, so a Java LocalDate always arrives as a string; typing it as Date would be
   * a lie and any Date method called on it would crash the screen.
   * Required, with no "?": without a start date the assignment could not be placed in any
   * period, and the workload plan would not know from when the person is available.
   */
  startDate: string;
  /**
   * The day the person left. MISSING means the person is still on the project - which is
   * exactly how the screen tells a current member from a past one. Writing today's date
   * instead of leaving it empty would mark everybody as gone every single day.
   */
  endDate?: string;
}

/**
 * The body SENT when somebody is added to the team (mirror of the Java record
 * TeamAssignmentRequest).
 *
 * WHY IT IS NOT THE SHAPE ABOVE
 * It carries no id and no projectId. The project id is in the URL
 * (/api/projects/{id}/team), which is exactly where ProjectScopeInterceptor reads it to
 * apply ADR-021. If it were in the body, a caller could keep the URL of his own project
 * and put someone else's id in the JSON, and add himself to a team he cannot see.
 * It also carries no projectName and no userFullName: those are looked up by the server
 * from the ids. Sending a name would let the browser decide what is displayed next to an
 * id that points somewhere else.
 */
export interface TeamAssignmentRequest {
  /** Which person to add. An id, never a name: the server looks it up, so an id that does
   *  not exist is refused instead of creating a row that points at nobody. */
  userId: number;
  /** The free-text job on this project. See the warning on TeamAssignment.roleInTeam: this
   *  is a label, not a permission. */
  roleInTeam?: string;
  startDate: string;
  endDate?: string;
}

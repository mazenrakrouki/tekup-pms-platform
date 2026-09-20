// TeamAssignment (server shape) and TeamAssignmentRequest (add-member body): an assignment
// links one person to one project over a date range, so the same person can leave and
// rejoin later and workload figures still know which period is which.

/** One person assigned to one project, as the server sends it (mirror of
 *  TeamAssignmentResponse). Carries both project code and name so neither the team tab
 *  (project known) nor a per-user assignment list (project unknown) needs a second call. */
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
  /** Free-text job on this project ("Developpeur back-end"). Unrelated to rbac.model.ts
   *  roles and never checked by any authorization code — it's a label, not a permission. */
  roleInTeam: string;
  /** ISO date text ("2026-07-14"), not Date. Required: an assignment needs a start to be
   *  placed in any period. */
  startDate: string;
  /** Missing means the person is still on the project — that's how the screen tells a
   *  current member from a past one. */
  endDate?: string;
}

/** Body sent when somebody is added to the team (mirror of TeamAssignmentRequest). No id or
 *  projectId — the project id comes from the URL, where ProjectScopeInterceptor enforces
 *  ADR-021; no projectName/userFullName since the server looks those up from the ids. */
export interface TeamAssignmentRequest {
  /** Which person to add, by id — an unknown id is refused rather than pointing at nobody. */
  userId: number;
  /** The free-text job on this project. See TeamAssignment.roleInTeam: a label, not a permission. */
  roleInTeam?: string;
  startDate: string;
  endDate?: string;
}

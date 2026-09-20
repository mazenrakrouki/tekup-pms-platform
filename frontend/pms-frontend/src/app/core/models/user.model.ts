// User (an account), UserCreateResult (the answer on account creation) and Resource (a
// person's cost sheet). User and Resource are deliberately separate: a User is an account
// anybody on the admin screen may see, a Resource is salary-adjacent cost data — keeping
// them apart lets MANAGE_USERS be granted without ever exposing VIEW_RESOURCES data.

/** One account of the application (mirror of UserResponse). Never carries a password or its
 *  hash — the server never sends either, so there's nothing for the browser to leak. */
export interface User {
  id: number;
  firstName: string;
  lastName: string;
  /** The e-mail is also the login. That is why the server refuses a duplicate. */
  email: string;
  /** The role name (e.g. "CHEF_PROJET"), display/filter only — nothing decides access by
   *  reading it, only by permission codes (see rbac.model.ts). */
  roleName: string;
  /** false once deactivated. Kept, not erased: the account is still named as the author of
   *  old rows (who declared/approved what), and deleting it would orphan that history. */
  active: boolean;
  /** true while the account still holds its temporary creation password; forces the
   *  change-password page (H-2) so the initial password can't be used forever. */
  firstLogin: boolean;
}

/** Answer for "create account" / "reset account" (mirror of UserCreateResult) — the only
 *  place in the app where a password travels from server to browser. Generated server-side
 *  (never chosen by an admin), shown once, then stored only as a bcrypt hash. Do not write
 *  it to localStorage, a URL, or leave it on screen after the dialog closes. */
export interface UserCreateResult {
  /** The account that was just created or reset. */
  user: User;
  /** The generated password, in clear text, sent exactly once and never retrievable again. */
  initialPassword: string;
}

/** The cost sheet of one person (mirror of ResourceResponse), at most one per User. Where
 *  the money side of a person lives, behind its own VIEW_RESOURCES/MANAGE_RESOURCES permission. */
export interface Resource {
  /** The id of the cost sheet itself, which is NOT the id of the user. */
  id: number;
  /** The person this sheet belongs to. The id is sent back when saving, the name printed. */
  userId: number;
  userFullName: string;
  /** Base cost of one man-day, before the overhead coefficient is applied. */
  dailyRate: number;
  /** TCC (overhead on top of the daily rate: social charges, office, tools), a FRACTION not
   *  a percentage — loaded daily cost is dailyRate x (1 + tccRate). Server rejects >= 10. */
  tccRate: number;
  /** Indicative loaded yearly cost (dailyRate x (1 + tccRate) x 218 working days), derived
   *  on read, never stored. An order of magnitude for the screen, not an accounting figure —
   *  margin calculations use the rate of the year each day was charged to (F-AFF-13). */
  annualCost?: number;
  /** ISO date range the person can be staffed. staffingEnd absent means no planned end
   *  (the normal case) — a far-away placeholder date would be misread as a real one later. */
  staffingStart?: string;
  staffingEnd?: string;
}

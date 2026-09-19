/*
 * FILE: user.model.ts
 *
 * WHAT THIS FILE IS
 * Three shapes about people: User, the account itself; UserCreateResult, the special
 * answer returned when an account is created; and Resource, the COST sheet attached to a
 * person (daily rate and overhead coefficient).
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot UserCrudService and ResourceService answer with the Java records
 *   UserResponse, UserCreateResult and ResourceResponse
 *     -> Jackson turns them into JSON
 *     -> features/admin/user-list/user-list.component.ts calls /api/users directly with
 *        HttpClient, typing the answer as PagedResponse<User> (see pagination.model.ts)
 *     -> features/resources/resources.component.ts does the same for Resource.
 *   Note that these two shapes have no service of their own in core/services: the two
 *   admin screens are the only callers, and they build their requests themselves.
 *
 * WHY USER AND RESOURCE ARE TWO SEPARATE SHAPES
 * They do not carry the same secret. A User is an account - anybody who can open the admin
 * screen may see it. A Resource says what a person COSTS the company, which is salary
 * information. Keeping them apart is what makes it possible to let somebody manage
 * accounts (MANAGE_USERS) without ever showing him a daily rate (VIEW_RESOURCES). Merged
 * into one shape, one answer would carry both and the separation would be impossible.
 *
 * SECURITY REMINDER
 * Nothing here protects anything; it only describes data. On the server, managing accounts
 * needs MANAGE_USERS, reading cost sheets needs VIEW_RESOURCES and changing them needs
 * MANAGE_RESOURCES, all checked with @PreAuthorize on the SERVICE methods. These URLs are
 * not under /api/projects/{id}/, so ProjectScopeInterceptor does not apply to them: here
 * the permission is the whole check.
 */

/**
 * One account of the application (mirror of the Java record UserResponse).
 *
 * NOTICE WHAT IS NOT IN IT: no password, and no hash of a password. The server never sends
 * either, in any shape, for any reason. A hash sent to the browser is a hash an attacker
 * can take away and try offline for as long as he likes.
 */
export interface User {
  id: number;
  firstName: string;
  lastName: string;
  /** The e-mail is also the login. That is why the server refuses a duplicate. */
  email: string;
  /**
   * The name of the role held by the account, for example "CHEF_PROJET".
   * It is only ever DISPLAYED and used to filter the list. Nothing in the application
   * decides what is allowed by reading it: the decisions are taken on the permission codes
   * (see auth.model.ts and rbac.model.ts). That is what makes the authorization dynamic -
   * an administrator can create a new role without a line of code being changed.
   */
  roleName: string;
  /**
   * false when the account has been deactivated. A deactivated account is kept, not
   * erased: it is still named as the author of old rows - who declared a timesheet, who
   * approved it - and deleting it would leave that history pointing at nobody.
   */
  active: boolean;
  /**
   * true while the account still carries the temporary password given at creation.
   * The login screen reads the same flag from AuthResponse and forces the user onto the
   * change-password page (H-2). Without it, an account could keep for ever the password
   * that was handed to it by whoever created it.
   */
  firstLogin: boolean;
}

/**
 * The answer of "create an account" and of "reset an account" (mirror of the Java record
 * UserCreateResult). It is the ONLY place in the whole application where a password ever
 * travels from the server to a browser.
 *
 * WHY THIS EXISTS AT ALL
 * The server generates the first password itself instead of letting an administrator
 * choose one. An administrator would pick something weak, and the same one for everybody.
 * Generated, it is shown ONCE, on this answer, so the administrator can pass it to the
 * person; it is stored only as a bcrypt hash (bcrypt is the slow hashing algorithm used
 * for passwords), so nobody - not even the administrator - can read it again afterwards.
 * The account comes back with firstLogin = true, so this password can only be used once
 * before the user is forced to change it.
 *
 * WHAT THE SCREEN MUST NOT DO WITH IT: write it into localStorage, put it in a URL, or
 * leave it on screen after the dialog is closed. It is meant to be read and passed on.
 */
export interface UserCreateResult {
  /** The account that was just created or reset. */
  user: User;
  /** The generated password, in clear text, sent exactly once and never retrievable again. */
  initialPassword: string;
}

/**
 * The cost sheet of one person (mirror of the Java record ResourceResponse).
 * There is at most one Resource per User.
 *
 * This is where the money side of a person lives, and it is what the KPI engine uses to
 * turn man-days into dinars. It is also why this shape is behind its own permission.
 */
export interface Resource {
  /** The id of the cost sheet itself, which is NOT the id of the user. */
  id: number;
  /** The person this sheet belongs to. The id is sent back when saving, the name printed. */
  userId: number;
  userFullName: string;
  /** Base cost of one man-day, before the overhead coefficient is applied. */
  dailyRate: number;
  /**
   * TCC: the overhead the company adds on top of the daily rate (social charges, office,
   * tools). It is a FRACTION, not a percentage: 0.42 means 42%, and the loaded cost of one
   * day is dailyRate x (1 + tccRate).
   * Why that matters here: a screen that displayed this value raw would show "0.42" where
   * the user expects "42 %", and anyone typing 42 into it would multiply every cost of the
   * company by 43. The server refuses a value of 10 or more, which is the second net under
   * exactly that mistake.
   */
  tccRate: number;
  /**
   * An INDICATIVE loaded yearly cost, computed by the server as
   * dailyRate x (1 + tccRate) x 218 working days.
   * It is derived when read and never stored in a column - the same rule as the Devis
   * Interne. Stored, it would have to be rewritten every time a rate changed, and the two
   * would end up disagreeing.
   * It is an order of magnitude for the resources screen, NOT an accounting figure: the
   * margin calculations do not go through it, they use the rate of the year each day was
   * charged to (spec F-AFF-13).
   */
  annualCost?: number;
  /**
   * First and last day the person can be staffed on a project, as plain ISO text
   * ("2026-07-14"). JSON has no date type, so a Java LocalDate always arrives as a string.
   * staffingEnd is missing when the person is available with no planned end, which is the
   * normal case for a permanent employee. A conventional far-away date such as 2099-12-31
   * would be read as a real end date by anybody writing a report later, which is why the
   * field is left empty instead.
   */
  staffingStart?: string;
  staffingEnd?: string;
}

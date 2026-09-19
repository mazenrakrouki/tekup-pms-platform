/*
 * FILE: auth.model.ts
 *
 * WHAT THIS FILE IS
 * The three shapes of the sign-in exchange, seen from the browser: what is SENT to log in
 * (LoginRequest), what the server SENDS BACK (AuthResponse), and what the application
 * KEEPS about the logged-in user while he is using it (UserContext).
 *
 * WHERE IT SITS IN THE FLOW
 *   features/auth/login/login.component.ts fills a LoginRequest
 *     -> core/services/auth.service.ts POSTs it to /api/auth/login
 *     -> Spring Boot AuthController -> AuthService checks the password with bcrypt
 *        (bcrypt is the slow hashing algorithm used to store passwords, so the real
 *        password is never kept anywhere) and answers with the Java record AuthResponse
 *     -> auth.service.ts turns that answer into a UserContext, writes it into localStorage
 *        and into a signal
 *     -> the guards (core/guards) and every screen read that signal to decide what to draw.
 *
 * WHY IT EXISTS
 * It is the written contract of the sign-in. Without it, auth.service.ts would handle the
 * answer as 'any', and 'any' switches the compiler off: reading 'res.token' instead of
 * 'res.accessToken' would compile, store undefined, and every later request would be sent
 * with "Bearer undefined" - the user would be logged out one second after logging in, with
 * no error pointing at the real mistake.
 *
 * THE TWO TOKENS - THE JURY ALWAYS ASKS
 * Only ONE of the two appears in this file.
 *  - The ACCESS token (accessToken below) is a short-lived JWT. A JWT (JSON Web Token) is
 *    a signed piece of text that says who you are; the server can check the signature
 *    without reading the database. It lives 15 minutes and is copied into the
 *    Authorization header of every request.
 *  - The REFRESH token is NOT in this file, on purpose. It travels in an HttpOnly cookie,
 *    which means JavaScript cannot read it - and that is exactly why it is safe there. It
 *    lives 7 days, or 30 days when "remember me" was ticked. Every refresh hands back a
 *    NEW cookie (rotation), and the server keeps a tokenVersion counter per user, so
 *    raising that counter kills every open session of that user at once.
 *    If the refresh token were declared here, it would mean JavaScript could read it, and
 *    any injected script on the page could steal a 30-day session.
 *
 * WHAT IS NOT SECURITY HERE
 * The permission list below only decides what is DRAWN on the screen. Anybody can edit it
 * with the browser tools. The real refusal is on the server, with
 * @PreAuthorize("hasAuthority('X')") on the SERVICE methods, plus ProjectScopeInterceptor
 * for the /api/projects/{id}/** URLs, which checks the permission AND the project scope
 * (ADR-021).
 */

/**
 * What the login form SENDS (mirror of the Java record LoginRequest).
 *
 * Why the password is a plain string here and that is not a flaw: it exists for the few
 * milliseconds of the POST, over HTTPS, and is never written to localStorage. On the
 * server it is compared against a bcrypt hash and never stored as typed.
 */
export interface LoginRequest {
  email: string;
  password: string;
  /**
   * Long session (30 days) instead of the default refresh lifetime (7 days).
   *
   * It is sent at login and never again: the server writes the choice INSIDE the refresh
   * token, and reads it back from there at each renewal. Without that, every renewal would
   * fall back to the short lifetime and a user who ticked the box would still be signed
   * out after 7 days - the application would be lying to him.
   */
  rememberMe: boolean;
}

/**
 * What /api/auth/login and /api/auth/refresh SEND BACK (mirror of the Java record
 * AuthResponse).
 *
 * Why the identity and the permissions come back together with the token, instead of a
 * second call to a /me endpoint: it saves one round trip on the slowest moment of the
 * application, and it keeps the two in step. With two calls, the token and the permission
 * list could be one refresh apart, and a menu would stay visible for a user who has just
 * lost the right to it.
 */
export interface AuthResponse {
  userId: number;
  /** The short-lived JWT that the HTTP interceptor puts in the Authorization header. */
  accessToken: string;
  /**
   * true when the account still carries the temporary password given at creation. The
   * login screen reads it and sends the user straight to the change-password page (H-2).
   * Without this flag a new account could keep for ever the password that was sent to it
   * by e-mail, and that password is known to whoever created the account.
   */
  firstLogin: boolean;
  email: string;
  fullName: string;
  /**
   * ONE role name, for example "CHEF_PROJET". It is only ever DISPLAYED.
   * Nothing in the code decides what is allowed by reading this value: the decisions are
   * taken on the permission codes below. That is what makes the authorisation dynamic - an
   * administrator can create a new role in the admin screens, and no line of TypeScript or
   * Java has to be changed for it to work.
   */
  role: string;
  /**
   * The permission codes of the user, for example ['VIEW_PROJECT', 'MANAGE_DI'].
   * On the Java side this is a Set; JSON has no set, so it arrives as an array.
   * The screens use it only to hide buttons the user cannot use. Hiding is comfort;
   * refusing is the server's job.
   */
  permissions: string[];
}

/**
 * The session as the BROWSER keeps it. It is NOT sent by the server: auth.service.ts
 * builds it from the AuthResponse above, stores it as JSON text in localStorage, and puts
 * it in a signal so every screen redraws when it changes.
 *
 * Why a separate shape rather than storing the AuthResponse itself: the access token must
 * not be duplicated inside it (it has its own localStorage key and its own lifetime), and
 * firstLogin is a one-off answer about the login, not a fact about the session. Keeping
 * the token in two places is how one copy ends up refreshed and the other stale.
 *
 * Why it is stored at all: the user presses F5. Without something saved, the signal would
 * restart empty, every guard would refuse, and a simple page reload would look like a
 * logout although the refresh cookie is still perfectly valid.
 */
export interface UserContext {
  userId: number;
  email: string;
  fullName: string;
  /**
   * An array although the server sends a single role: auth.service.ts wraps it with
   * 'roles: [res.role]'. The shape is kept open so a user holding several roles later
   * needs no change in the screens that display it.
   */
  roles: string[];
  /** The same permission codes as above, and the only thing the guards ever read. */
  permissions: string[];
}

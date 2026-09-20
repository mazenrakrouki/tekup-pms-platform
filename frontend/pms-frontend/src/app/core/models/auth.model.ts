// The three shapes of the sign-in exchange: what's SENT to log in (LoginRequest), what the
// server SENDS BACK (AuthResponse, mirroring the Java record), and what the app KEEPS about
// the logged-in user (UserContext, built by auth.service.ts from AuthResponse). The refresh
// token deliberately has no field here: it lives in an HttpOnly cookie JavaScript can't
// read, rotates on every refresh, and is revocable in bulk via a server-side tokenVersion
// counter. The permissions array only controls what's drawn; the server enforces access via
// @PreAuthorize + ProjectScopeInterceptor (ADR-021).

/** What the login form SENDS (mirror of LoginRequest). The password exists only for the
 *  POST over HTTPS and is never persisted client-side. */
export interface LoginRequest {
  email: string;
  password: string;
  /** 30-day session instead of the default 7. Sent once at login; the server encodes the
   *  choice into the refresh token itself and reads it back on each renewal. */
  rememberMe: boolean;
}

/**
 * What /api/auth/login and /api/auth/refresh SEND BACK (mirror of AuthResponse). Identity
 * and permissions travel with the token rather than a separate /me call, so they can't drift
 * out of sync.
 */
export interface AuthResponse {
  userId: number;
  /** Short-lived JWT the HTTP interceptor puts in the Authorization header. */
  accessToken: string;
  /** true while the account still holds its temporary password; routes to change-password (H-2). */
  firstLogin: boolean;
  email: string;
  fullName: string;
  /** One role name, display-only. Authorization decisions use the permission codes below,
   *  never this - that's what keeps roles dynamic (admin-created, no code change needed). */
  role: string;
  /** Permission codes, e.g. ['VIEW_PROJECT', 'MANAGE_DI']. Used only to hide UI; the server
   *  is the one that actually refuses. */
  permissions: string[];
}

/**
 * The session as the BROWSER keeps it (not sent by the server). Kept separate from
 * AuthResponse so the access token isn't duplicated across two lifetimes, and persisted to
 * localStorage so an F5 reload doesn't look like a logout while the refresh cookie is valid.
 */
export interface UserContext {
  userId: number;
  email: string;
  fullName: string;
  /** Array even though the server sends one role ('roles: [res.role]'), so a future
   *  multi-role user needs no screen changes. */
  roles: string[];
  /** The same permission codes as above; the only thing the guards read. */
  permissions: string[];
}

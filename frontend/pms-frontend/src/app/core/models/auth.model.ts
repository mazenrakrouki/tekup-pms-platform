export interface LoginRequest {
  email: string;
  password: string;
  /** Session longue (30 jours) au lieu de la duree de rafraichissement par defaut. */
  rememberMe: boolean;
}

export interface AuthResponse {
  userId: number;
  accessToken: string;
  firstLogin: boolean;
  email: string;
  fullName: string;
  role: string;
  permissions: string[];
}

export interface UserContext {
  userId: number;
  email: string;
  fullName: string;
  roles: string[];
  permissions: string[];
}

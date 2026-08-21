export interface LoginRequest {
  email: string;
  password: string;
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

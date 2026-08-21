export interface User {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
  roleName: string;
  active: boolean;
  firstLogin: boolean;
}

export interface UserCreateResult {
  user: User;
  initialPassword: string;
}

export interface Resource {
  id: number;
  userId: number;
  userFullName: string;
  dailyRate: number;
  tccRate: number;
  annualCost?: number;
  staffingStart?: string;
  staffingEnd?: string;
}

export interface PlanCharge {
  id: number;
  projectId: number;
  projectCode: string;
  userId: number;
  userFullName: string;
  year: number;
  month: number;
  plannedDays: number;
}

export interface ChargeReelle {
  id: number;
  projectId: number;
  projectCode: string;
  userId: number;
  userFullName: string;
  year: number;
  month: number;
  actualDays: number;
  submittedAt?: string;
  validatedAt?: string;
  validatedById?: number;
  validatedByName?: string;
}

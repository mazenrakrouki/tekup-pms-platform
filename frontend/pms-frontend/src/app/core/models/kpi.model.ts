export interface KpiResponse {
  snapshotId?: number;
  projectId: number;
  projectCode: string;
  snapshotDate?: string;
  budgetPlanifie: number;
  budgetConsome: number;
  eac: number;
  marge: number;
  tauxConsommation: number;
}

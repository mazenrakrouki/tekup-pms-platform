export type ProjectStatus = 'DRAFT' | 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'CANCELLED';
export type BusinessModel = 'SEUL' | 'GROUPEMENT';
export type EngagementType = 'FORFAIT' | 'REGIE';

export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  DRAFT: 'Brouillon',
  ACTIVE: 'Actif',
  ON_HOLD: 'En pause',
  COMPLETED: 'Terminé',
  CANCELLED: 'Annulé'
};

export interface Project {
  id: number;
  code: string;
  name: string;
  description?: string;
  status: ProjectStatus;
  startDate?: string;
  endDate?: string;
  initialBudget?: number;
  revisedBudget?: number;
  effectiveBudget?: number;
  directorId?: number;
  directorName?: string;
  chefProjetId?: number;
  chefProjetName?: string;

  // Fiche d'identification (modèle Excel)
  contractId?: string;
  client?: string;
  funder?: string;
  businessModel?: BusinessModel;
  engagementType?: EngagementType;
  currency?: string;
  exchangeRateToTnd?: number;
  licenseSubcontractBudget?: number;
  soldWorkloadDays?: number;
  warrantyWorkloadDays?: number;
  penaltyProvision?: number;

  archived?: boolean;

  // Calculés
  durationDays?: number;
  budgetTnd?: number;
  pprTnd?: number;
}

export interface ProjectRequest {
  code: string;
  name: string;
  description?: string;
  status: ProjectStatus;
  startDate?: string;
  endDate?: string;
  initialBudget?: number;
  directorId?: number;
  chefProjetId?: number;

  contractId?: string;
  client?: string;
  funder?: string;
  businessModel?: BusinessModel;
  engagementType?: EngagementType;
  currency?: string;
  exchangeRateToTnd?: number;
  licenseSubcontractBudget?: number;
  soldWorkloadDays?: number;
  warrantyWorkloadDays?: number;
  penaltyProvision?: number;
}

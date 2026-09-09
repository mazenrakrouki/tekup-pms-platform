export type SprintStatus = 'PLANNED' | 'ACTIVE' | 'CLOSED';
export type BacklogItemStatus = 'TODO' | 'IN_PROGRESS' | 'DONE';
export type BacklogPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export const SPRINT_STATUSES: SprintStatus[] = ['PLANNED', 'ACTIVE', 'CLOSED'];
export const BACKLOG_STATUSES: BacklogItemStatus[] = ['TODO', 'IN_PROGRESS', 'DONE'];
export const BACKLOG_PRIORITIES: BacklogPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export interface Sprint {
  id: number;
  projectId: number;
  projectCode: string;
  name: string;
  goal?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  status: SprintStatus;
}

export interface BacklogItem {
  id: number;
  projectId: number;
  projectCode: string;
  /** null = backlog produit, non engagé dans un sprint. */
  sprintId?: number | null;
  sprintName?: string | null;
  title: string;
  description?: string | null;
  priority: BacklogPriority;
  /** Estimation en jours-homme (JH). */
  estimateDays?: number | null;
  status: BacklogItemStatus;
  /** null = personne n'a encore pris l'élément en charge. */
  assigneeId?: number | null;
  assigneeName?: string | null;
}

export interface SprintPayload {
  name: string;
  goal?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  status: SprintStatus;
}

export interface BacklogItemPayload {
  title: string;
  description?: string | null;
  priority: BacklogPriority;
  estimateDays?: number | null;
  status: BacklogItemStatus;
  sprintId?: number | null;
  assigneeId?: number | null;
}

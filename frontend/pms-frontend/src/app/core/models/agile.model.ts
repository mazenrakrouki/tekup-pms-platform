// Browser-side shapes for the Agile module: sprint, backlog item, and their create/update
// payloads. Mirrors the Java records SprintResponse/BacklogItemResponse returned by
// SprintController/BacklogItemController; agile.service.ts and agile.component.ts (the
// Scrum board) are the consumers. Field names match the Java records exactly - do not
// rename them to read better, that would silently desync from the real JSON. Nothing here
// enforces access: reading needs VIEW_AGILE, writing needs MANAGE_AGILE, checked
// server-side via @PreAuthorize plus ProjectScopeInterceptor (ADR-021).

// Union types, not plain strings, so a typo like 'ACTIVED' is refused at build time instead
// of round-tripping to the server as a 400.
export type SprintStatus = 'PLANNED' | 'ACTIVE' | 'CLOSED';
// Also the three columns of the board, in this order.
export type BacklogItemStatus = 'TODO' | 'IN_PROGRESS' | 'DONE';
export type BacklogPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

// Real runtime arrays (unlike the types above), used to build dropdowns/columns by loop.
// Typed explicitly so an entry added here without updating the union type is a build error.
export const SPRINT_STATUSES: SprintStatus[] = ['PLANNED', 'ACTIVE', 'CLOSED'];
// Read by agile.component.ts as `columns`; order = board order.
export const BACKLOG_STATUSES: BacklogItemStatus[] = ['TODO', 'IN_PROGRESS', 'DONE'];
// Read by agile.component.ts as `priorities`, least to most urgent.
export const BACKLOG_PRIORITIES: BacklogPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

/**
 * One sprint as the server SENDS it (mirror of SprintResponse). Separate from SprintPayload
 * below because the response carries id/projectId, which the browser must never set itself.
 */
export interface Sprint {
  id: number;
  projectId: number;
  /** Readable project code, e.g. "S2I-2026-014", sent alongside the id to avoid a second call. */
  projectCode: string;
  name: string;
  // "?" = may be absent; "| null" = may be present and empty (Jackson writes null for empty).
  goal?: string | null;
  // ISO date text ("2026-07-14"), not a Date: JSON has no date type.
  startDate?: string | null;
  endDate?: string | null;
  status: SprintStatus;
}

/**
 * One backlog item as the server SENDS it (mirror of BacklogItemResponse): a story, task or bug.
 */
export interface BacklogItem {
  id: number;
  projectId: number;
  projectCode: string;
  /** null = product backlog, not committed to a sprint yet. */
  sprintId?: number | null;
  sprintName?: string | null;
  title: string;
  description?: string | null;
  priority: BacklogPriority;
  /** Estimate in man-days (JH, "jours-homme"); 0.5 = half a day. */
  estimateDays?: number | null;
  status: BacklogItemStatus;
  /** null = nobody has taken the item yet. */
  assigneeId?: number | null;
  assigneeName?: string | null;
}

/**
 * Body SENT to create/update a sprint (mirror of SprintRequest). No id/projectId: both
 * live in the URL, where ProjectScopeInterceptor reads the project id (ADR-021).
 */
export interface SprintPayload {
  name: string;
  goal?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  status: SprintStatus;
}

/**
 * Body SENT to create/update a backlog item (mirror of BacklogItemRequest). sprintId moves
 * the item between backlog and sprint (null = detach); one save can move and re-status a
 * card at once, which is what a drag between columns needs.
 */
export interface BacklogItemPayload {
  title: string;
  description?: string | null;
  priority: BacklogPriority;
  estimateDays?: number | null;
  status: BacklogItemStatus;
  sprintId?: number | null;
  assigneeId?: number | null;
}

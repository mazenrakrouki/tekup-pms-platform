export interface TeamAssignment {
  id: number;
  projectId: number;
  projectCode: string;
  projectName: string;
  userId: number;
  userFullName: string;
  roleInTeam: string;
  startDate: string;
  endDate?: string;
}

export interface TeamAssignmentRequest {
  userId: number;
  roleInTeam?: string;
  startDate: string;
  endDate?: string;
}

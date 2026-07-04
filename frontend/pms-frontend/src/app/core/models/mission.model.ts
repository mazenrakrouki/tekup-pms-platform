export type TypeComposante = 'PERDIEM' | 'BILLET' | 'TIMBRE' | 'TRANSPORT' | 'SEJOUR';

export interface Mission {
  id: number;
  projectId: number;
  projectCode: string;
  userId: number;
  userFullName: string;
  objet: string;
  lieu: string;
  dateDebut: string;
  dateFin: string;
}

export interface Composante {
  id: number;
  missionId: number;
  typeComposante: TypeComposante;
  montant: number;
  devise: string;
  description?: string;
}

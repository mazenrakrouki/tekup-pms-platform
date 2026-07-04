export type NiveauRisque = 'FAIBLE' | 'MOYEN' | 'ELEVE';
export type StatutRisque = 'OUVERT' | 'MITIGE' | 'FERME';
export type StatutLivrable = 'EN_ATTENTE' | 'EN_COURS' | 'LIVRE' | 'VALIDE';
export type StatutChangement = 'EN_ATTENTE' | 'APPROUVE' | 'REJETE';
export type PrioriteChangement = 'FAIBLE' | 'NORMALE' | 'ELEVEE' | 'CRITIQUE';

export interface Risk {
  id: number;
  projectId: number;
  projectCode: string;
  description: string;
  probabilite: NiveauRisque;
  impact: NiveauRisque;
  planMitigation?: string;
  statut: StatutRisque;
}

export interface Livrable {
  id: number;
  projectId: number;
  projectCode: string;
  titre: string;
  description?: string;
  dateEcheance?: string;
  statut: StatutLivrable;
}

export interface DemandeChangement {
  id: number;
  projectId: number;
  projectCode: string;
  demandeurId: number;
  demandeurFullName: string;
  titre: string;
  description?: string;
  priorite: PrioriteChangement;
  statut: StatutChangement;
  dateDemande?: string;
  dateDecision?: string;
}
